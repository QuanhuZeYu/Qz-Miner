package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 洪泛搜索规划策略公共主流程。
 */
public abstract class AbstractFloodFillPlanningStrategy implements ChainPlanningStrategy {

    private static final int MAX_SCAN_PER_SLICE = 64;

    private final ChainMode mode;
    private final BlockSeedResolver blockSeedResolver = new WorldBlockSeedResolver();

    protected AbstractFloodFillPlanningStrategy(ChainMode mode) {
        this.mode = mode;
    }

    @Override
    public boolean supports(ChainMode mode) {
        return this.mode == mode;
    }

    /**
     * 执行洪泛规划公共主流程。
     *
     * @param player 服务端玩家
     * @param playerState 玩家状态
     * @param origin 连锁起点
     * @param session 预构造的会话
     */
    protected final void startPlanningInternal(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin, ChainSession session) {
        BlockSeedSnapshot seedSnapshot = blockSeedResolver.resolve(player, origin);
        if (seedSnapshot == null) {
            return;
        }

        if (!checkCanOperate(player, playerState) || session == null) {
            return;
        }

        playerState.clearRuntimeState(getRestartReason());
        playerState.setSession(session);
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING, getStartReason());
        session.getRuntimeState().setPlannerRunning(true);
        session.getRuntimeState().setPlannerCompleted(false);
        session.getRuntimeState().resetExecutorThrottle();
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = session.getRuntimeState().getPendingBreakTargets();
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
            getTaskPrefix() + playerUUID,
            context -> {
                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, getStopReasonPrefix() + "player-unavailable");
                    return false;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null || currentState.getSession() == null) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, getStopReasonPrefix() + "state-missing");
                    return false;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, getStopReasonPrefix() + "key-released");
                    return false;
                }

                ChainSession currentSession = currentState.getSession();
                int previousMatchedCount = currentSession.getRuntimeState().getMatchedTargetCount();

                boolean shouldContinue = traverser.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    target -> blockMatcher.matches((EntityPlayerMP) currentPlayer, target),
                    queue::add);
                currentSession.getRuntimeState().setMatchedTargetCount(searchContext.getConfirmedCount());
                boolean matchedCountChanged = previousMatchedCount != currentSession.getRuntimeState().getMatchedTargetCount();

                if (!queue.isEmpty() && currentState.getExecutionStatus() == ChainExecutionStatus.PLANNING) {
                    currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, getPlannerReasonPrefix() + "found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                } else if (matchedCountChanged) {
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    logPlanCompleted(playerUUID, searchContext, queue, currentState);
                    currentSession.getRuntimeState().setPlannerSubscription(null);
                    currentSession.getRuntimeState().setPlannerRunning(false);
                    currentSession.getRuntimeState().setPlannerCompleted(true);
                    currentSession.getRuntimeState().setMatchedTargetCount(searchContext.getConfirmedCount());
                    searchContext.getCurrentFrontier().clear();
                    if (queue.isEmpty()) {
                        currentState.setExecutionStatus(ChainExecutionStatus.IDLE, getPlannerReasonPrefix() + "completed-empty-queue");
                        currentState.clearSession();
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else if (currentState.getExecutionStatus() != ChainExecutionStatus.RUNNING) {
                        currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, getPlannerReasonPrefix() + "completed-with-targets");
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else {
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                return shouldContinue;
            });

        session.getRuntimeState().setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started {} plan for player {} at ({}, {}, {}), radius={}, maxBlocks={}",
            getLogLabel(), playerUUID, origin.getX(), origin.getY(), origin.getZ(), Config.chainRadius, Config.chainMaxBlocks);
    }

    /**
     * 创建单次规划运行时。
     *
     * @param player 当前玩家
     * @param session 连锁会话
     * @param seedSnapshot 方块种子快照
     * @return 规划运行时
     */
    protected ChainPlanningRuntime createPlanningRuntime(EntityPlayerMP player, ChainSession session, BlockSeedSnapshot seedSnapshot) {
        return ChainPlanningRuntimeFactory.createForServer(player.worldObj, player, session, seedSnapshot, false);
    }

    /**
     * 判断当前玩家是否允许启动规划。
     */
    protected abstract boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState);

    /**
     * 返回重启规划前清理状态使用的原因。
     */
    protected abstract String getRestartReason();

    /**
     * 返回进入规划态使用的原因。
     */
    protected abstract String getStartReason();

    /**
     * 返回任务注册前缀。
     */
    protected abstract String getTaskPrefix();

    /**
     * 返回停止规划使用的原因前缀。
     */
    protected abstract String getStopReasonPrefix();

    /**
     * 返回规划状态流转使用的原因前缀。
     */
    protected abstract String getPlannerReasonPrefix();

    /**
     * 返回日志中的模式标签。
     */
    protected abstract String getLogLabel();

    /**
     * 记录规划完成日志。
     */
    protected abstract void logPlanCompleted(UUID playerUUID, ChainSearchContext searchContext, ConcurrentLinkedQueue<ChainTarget> queue, ChainPlayerState currentState);
}
