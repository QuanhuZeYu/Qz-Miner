package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 默认的范围盒扫规划策略。
 */
public class BlockBoxScanPlanningStrategy implements ChainPlanningStrategy {

    private static final int MAX_SCAN_PER_SLICE = 64;
    private final ChainTraverser traverser = new BoxScanTraverser();
    private final BlockSeedResolver blockSeedResolver = new WorldBlockSeedResolver();

    /**
     * 判断当前策略是否支持指定模式。
     *
     * @param mode 连锁模式
     * @return 是否支持
     */
    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.AREA;
    }

    /**
     * 启动范围盒扫规划任务。
     *
     * @param player 服务端玩家
     * @param playerState 玩家连锁状态
     * @param origin 连锁起点
     */
    @Override
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
        BlockSeedSnapshot seedSnapshot = blockSeedResolver.resolve(player, origin);
        if (seedSnapshot == null) {
            return;
        }

        if (!checkCanOperate(player, playerState)) {
            return;
        }

        playerState.clearRuntimeState("restart-area-plan");
        ChainSession session = new ChainSession(
            player.getUniqueID(),
            playerState.getSelectedMode(),
            playerState.getSelectedSubMode(),
            origin);
        playerState.setSession(session);
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING, "start-area-plan");
        session.setPlannerRunning(true);
        session.setPlannerCompleted(false);
        session.updatePlannerHeartbeat();
        session.resetExecutorThrottle();
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = session.getPendingBreakTargets();
        final UUID playerUUID = player.getUniqueID();
        final ChainSearchContext searchContext = createSearchContext(player.worldObj, session, seedSnapshot);
        final ChainBlockMatcher blockMatcher = createBlockMatcher(searchContext);
        if (blockMatcher == null) {
            return;
        }
        traverser.seed(searchContext);

        ParallelTickSubscription subscription = MyMod.ensureParallelTickExecutor().registerPre(
            "area-plan-" + playerUUID,
            context -> {
                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "area-plan-player-unavailable");
                    return false;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null || currentState.getSession() == null) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "area-plan-state-missing");
                    return false;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "area-plan-key-released");
                    return false;
                }

                ChainSession currentSession = currentState.getSession();
                currentSession.updatePlannerHeartbeat();
                int previousMatchedCount = currentSession.getMatchedTargetCount();

                boolean shouldContinue = traverser.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    target -> blockMatcher.matches((EntityPlayerMP) currentPlayer, target),
                    queue::add);
                currentSession.setMatchedTargetCount(searchContext.getConfirmedCount());
                boolean matchedCountChanged = previousMatchedCount != currentSession.getMatchedTargetCount();

                if (!queue.isEmpty() && currentState.getExecutionStatus() == ChainExecutionStatus.PLANNING) {
                    currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "area-planner-found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                } else if (matchedCountChanged) {
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    MyMod.LOG.debug("[ChainPlanner] Area plan completed for player {}, confirmed={}, queuedTargets={}, pendingDrops={}",
                        playerUUID, searchContext.getConfirmedCount(), queue.size(), currentState.getPendingDrops().size());
                    currentSession.setPlannerSubscription(null);
                    currentSession.setPlannerRunning(false);
                    currentSession.setPlannerCompleted(true);
                    currentSession.setMatchedTargetCount(searchContext.getConfirmedCount());
                    searchContext.getCurrentFrontier().clear();
                    if (queue.isEmpty()) {
                        currentState.setExecutionStatus(ChainExecutionStatus.IDLE, "area-planner-completed-empty-queue");
                        currentState.clearSession();
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else if (currentState.getExecutionStatus() != ChainExecutionStatus.RUNNING) {
                        currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "area-planner-completed-with-targets");
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else {
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                return shouldContinue;
            });

        session.setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started area plan for player {} at ({}, {}, {}), radius={}, maxBlocks={}",
            playerUUID, origin.getX(), origin.getY(), origin.getZ(), Config.chainRadius, Config.chainMaxBlocks);
    }

    /**
     * 创建范围盒扫所需的搜索上下文。
     *
     * @param world 当前世界
     * @param session 连锁会话
     * @param seedSnapshot 方块种子快照
     * @return 搜索上下文
     */
    private ChainSearchContext createSearchContext(net.minecraft.world.World world, ChainSession session, BlockSeedSnapshot seedSnapshot) {
        session.getTraversalTargets().clear();
        return ChainSearchContextFactory.createBlockBoxScanContext(world, session, seedSnapshot);
    }

    /**
     * 根据当前子模式创建方块匹配器。
     *
     * @param searchContext 搜索上下文
     * @return 匹配器
     */
    private ChainBlockMatcher createBlockMatcher(ChainSearchContext searchContext) {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(ChainMode.AREA);
        if (definition != null) {
            return definition.createMatcher(searchContext);
        }
        return null;
    }

    /**
     * 判断当前玩家是否允许启动范围连锁。
     *
     * @param player 服务端玩家
     * @param playerState 玩家连锁状态
     * @return 是否允许启动
     */
    private boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState) {
        return playerState.isChainKeyPressed() && ChainHarvestRules.hasEnoughDurability(player);
    }
}
