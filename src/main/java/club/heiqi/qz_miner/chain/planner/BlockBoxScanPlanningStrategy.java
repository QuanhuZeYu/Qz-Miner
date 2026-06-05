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
import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 默认的范围盒扫规划策略。
 */
public class BlockBoxScanPlanningStrategy implements ChainPlanningStrategy {

    private static final int MAX_SCAN_PER_SLICE = 64;
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
            origin,
            AxisAlignedTunnelDirection.resolveFace(player),
            0.0F,
            0.0F,
            0.0F,
            playerState.getRequestedChainRadius(),
            playerState.getRequestedChainMaxBlocks());
        playerState.setSession(session);
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING, "start-area-plan");
        session.beginPlanning();
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = session.getPendingBreakTargets();
        final UUID playerUUID = player.getUniqueID();
        final ChainPlanningRuntime runtime = createPlanningRuntime(player, session, seedSnapshot);
        if (runtime == null) {
            return;
        }
        final ChainSearchContext searchContext = runtime.getSearchContext();
        final ChainTraverser traverser = runtime.getTraverser();
        final ChainBlockMatcher blockMatcher = runtime.getMatcher();
        traverser.seed(searchContext);

        ParallelTickSubscription subscription = MyMod.ensureParallelTickExecutor().registerPre(
            "area-plan-" + playerUUID,
            control -> {
                if (control.isCancelRequested()) {
                    return ParallelTaskResult.TERMINATED;
                }

                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "area-plan-player-unavailable");
                    return ParallelTaskResult.TERMINATED;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "area-plan-state-missing");
                    return ParallelTaskResult.TERMINATED;
                }

                if (!currentState.isSessionActive(session)) {
                    MyMod.LOG.debug("[ChainPlanner] Ignore stale area session for player {}", playerUUID);
                    return ParallelTaskResult.TERMINATED;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "area-plan-key-released");
                    return ParallelTaskResult.TERMINATED;
                }

                if (control.shouldYield()) {
                    return ParallelTaskResult.YIELDED;
                }

                ChainSession currentSession = session;
                int previousMatchedCount = currentSession.getMatchedTargetCount();

                TraversalStepResult traversalResult = ChainTraversalSupport.step(
                    traverser,
                    searchContext,
                    control,
                    MAX_SCAN_PER_SLICE,
                    target -> !control.isCancelRequested()
                        && currentState.isSessionActive(session)
                        && currentState.isChainKeyPressed()
                        && blockMatcher.matches((EntityPlayerMP) currentPlayer, target),
                    target -> {
                        if (!control.isCancelRequested()
                            && currentState.isSessionActive(session)
                            && currentState.isChainKeyPressed()) {
                            queue.add(target);
                        }
                    });
                if (traversalResult == TraversalStepResult.TERMINATED) {
                    return ParallelTaskResult.TERMINATED;
                }

                if (control.isCancelRequested() || !currentState.isSessionActive(session)) {
                    return ParallelTaskResult.TERMINATED;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "area-plan-key-released");
                    return ParallelTaskResult.TERMINATED;
                }

                currentSession.setMatchedTargetCount(searchContext.getConfirmedCount());
                boolean matchedCountChanged = previousMatchedCount != currentSession.getMatchedTargetCount();
                boolean shouldContinue = traversalResult == TraversalStepResult.CONTINUE || traversalResult == TraversalStepResult.YIELDED;

                if (!queue.isEmpty() && currentState.getExecutionStatus() == ChainExecutionStatus.PLANNING) {
                    currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "area-planner-found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                } else if (matchedCountChanged) {
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    int pendingDrops = currentState.getDropBuffer().size();
                    MyMod.LOG.debug("[ChainPlanner] Area plan completed for player {}, confirmed={}, queuedTargets={}, pendingDrops={}",
                        playerUUID, searchContext.getConfirmedCount(), queue.size(), pendingDrops);
                    currentSession.markPlanningCompleted();
                    currentSession.setMatchedTargetCount(searchContext.getConfirmedCount());
                    searchContext.getCurrentFrontier().clear();
                    if (queue.isEmpty()) {
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else if (currentState.getExecutionStatus() != ChainExecutionStatus.RUNNING) {
                        currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "area-planner-completed-with-targets");
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else {
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                if (shouldContinue && control.shouldYield()) {
                    return ParallelTaskResult.YIELDED;
                }

                return ChainTraversalSupport.toParallelTaskResult(traversalResult);
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
    private ChainPlanningRuntime createPlanningRuntime(EntityPlayerMP player, ChainSession session, BlockSeedSnapshot seedSnapshot) {
        return ChainPlanningRuntimeFactory.createForServer(player.worldObj, player, session, seedSnapshot);
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
