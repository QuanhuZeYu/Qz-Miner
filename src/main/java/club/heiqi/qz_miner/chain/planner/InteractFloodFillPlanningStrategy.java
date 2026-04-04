package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * INTERACT 默认邻居洪泛规划策略。
 */
public class InteractFloodFillPlanningStrategy implements ChainPlanningStrategy {

    private static final int MAX_SCAN_PER_SLICE = 64;
    private final ChainTraverser traverser = new FloodFillTraverser();
    private final BlockSeedResolver blockSeedResolver = new WorldBlockSeedResolver();

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.INTERACT;
    }

    @Override
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
        startPlanning(player, playerState, origin, 1, 0.0F, 0.0F, 0.0F);
    }

    /**
     * 使用指定点击面启动交互规划。
     *
     * @param player 服务端玩家
     * @param playerState 玩家连锁状态
     * @param origin 连锁起点
     * @param interactFace 交互点击面
     */
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin, int interactFace) {
        startPlanning(player, playerState, origin, interactFace, 0.0F, 0.0F, 0.0F);
    }

    /**
     * 使用指定点击面和命中点启动交互规划。
     *
     * @param player 服务端玩家
     * @param playerState 玩家连锁状态
     * @param origin 连锁起点
     * @param interactFace 交互点击面
     * @param interactHitX 命中点 X 偏移
     * @param interactHitY 命中点 Y 偏移
     * @param interactHitZ 命中点 Z 偏移
     */
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin, int interactFace, float interactHitX, float interactHitY, float interactHitZ) {
        BlockSeedSnapshot seedSnapshot = blockSeedResolver.resolve(player, origin);
        if (seedSnapshot == null) {
            return;
        }

        if (!checkCanOperate(player, playerState)) {
            return;
        }

        playerState.clearRuntimeState("restart-interact-plan");
        ChainSession session = new ChainSession(
            player.getUniqueID(),
            playerState.getSelectedMode(),
            playerState.getSelectedSubMode(),
            origin,
            interactFace,
            interactHitX,
            interactHitY,
            interactHitZ);
        playerState.setSession(session);
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING, "start-interact-plan");
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
            "interact-plan-" + playerUUID,
            context -> {
                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "interact-plan-player-unavailable");
                    return false;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null || currentState.getSession() == null) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "interact-plan-state-missing");
                    return false;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "interact-plan-key-released");
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
                    currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "interact-planner-found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                } else if (matchedCountChanged) {
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    MyMod.LOG.debug("[ChainPlanner] Interact plan completed for player {}, confirmed={}, queuedTargets={}",
                        playerUUID, searchContext.getConfirmedCount(), queue.size());
                    currentSession.setPlannerSubscription(null);
                    currentSession.setPlannerRunning(false);
                    currentSession.setPlannerCompleted(true);
                    currentSession.setMatchedTargetCount(searchContext.getConfirmedCount());
                    searchContext.getCurrentFrontier().clear();
                    if (queue.isEmpty()) {
                        currentState.setExecutionStatus(ChainExecutionStatus.IDLE, "interact-planner-completed-empty-queue");
                        currentState.clearSession();
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else if (currentState.getExecutionStatus() != ChainExecutionStatus.RUNNING) {
                        currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "interact-planner-completed-with-targets");
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else {
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                return shouldContinue;
            });

        session.setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started interact plan for player {} at ({}, {}, {}), radius={}, maxBlocks={}",
            playerUUID, origin.getX(), origin.getY(), origin.getZ(), Config.chainRadius, Config.chainMaxBlocks);
    }

    /**
     * 创建交互模式所需的搜索上下文。
     *
     * @param world 当前世界
     * @param session 连锁会话
     * @param seedSnapshot 方块种子快照
     * @return 搜索上下文
     */
    private ChainSearchContext createSearchContext(net.minecraft.world.World world, ChainSession session, BlockSeedSnapshot seedSnapshot) {
        session.getTraversalTargets().clear();
        return ChainSearchContextFactory.createBlockFloodFillContext(world, session, seedSnapshot);
    }

    /**
     * 根据模式定义创建交互匹配器。
     *
     * @param searchContext 搜索上下文
     * @return 匹配器
     */
    private ChainBlockMatcher createBlockMatcher(ChainSearchContext searchContext) {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(ChainMode.INTERACT);
        return definition == null ? null : definition.createMatcher(searchContext);
    }

    /**
     * 判断当前玩家是否允许启动交互连锁。
     *
     * @param player 服务端玩家
     * @param playerState 玩家连锁状态
     * @return 是否允许启动
     */
    private boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState) {
        return player != null && playerState.isChainKeyPressed();
    }
}
