package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 当前默认的方块洪泛规划策略。
 */
public class BlockFloodFillPlanningStrategy implements ChainPlanningStrategy {

    private static final int MAX_SCAN_PER_SLICE = 64;
    private final ChainTraverser traverser = new FloodFillTraverser();
    private final ChainBlockMatcher blockMatcher = new HarvestableBlockMatcher();
    private final BlockSeedResolver blockSeedResolver = new WorldBlockSeedResolver();

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.CHAIN;
    }

    @Override
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
        BlockSeedSnapshot seedSnapshot = blockSeedResolver.resolve(player, origin);
        if (seedSnapshot == null) {
            return;
        }

        if (!checkCanOperate(player, playerState)) {
            return;
        }

        playerState.clearRuntimeState("restart-plan");
        ChainSession session = new ChainSession(
            player.getUniqueID(),
            playerState.getSelectedMode(),
            ChainModeRegistry.getDefaultSubMode(ChainMode.CHAIN),
            origin);
        playerState.setSession(session);
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING, "start-plan");
        session.setPlannerRunning(true);
        session.setPlannerCompleted(false);
        session.updatePlannerHeartbeat();
        session.resetExecutorThrottle();
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = session.getPendingBreakTargets();
        final UUID playerUUID = player.getUniqueID();
        final ChainSearchContext searchContext = createSearchContext(player.worldObj, session, seedSnapshot);
        traverser.seed(searchContext);

        ParallelTickSubscription subscription = MyMod.ensureParallelTickExecutor().registerPre(
            "chain-plan-" + playerUUID,
            context -> {
                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-player-unavailable");
                    return false;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null || currentState.getSession() == null) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-state-missing");
                    return false;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-key-released");
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
                    currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "planner-found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                } else if (matchedCountChanged) {
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    MyMod.LOG.debug("[ChainPlanner] Plan completed for player {}, confirmed={}, queuedTargets={}, pendingDrops={}",
                        playerUUID, searchContext.getConfirmedCount(), queue.size(), currentState.getPendingDrops().size());
                    currentSession.setPlannerSubscription(null);
                    currentSession.setPlannerRunning(false);
                    currentSession.setPlannerCompleted(true);
                    currentSession.setMatchedTargetCount(searchContext.getConfirmedCount());
                    searchContext.getCurrentFrontier().clear();
                    if (queue.isEmpty()) {
                        currentState.setExecutionStatus(ChainExecutionStatus.IDLE, "planner-completed-empty-queue");
                        currentState.clearSession();
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else if (currentState.getExecutionStatus() != ChainExecutionStatus.RUNNING) {
                        currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "planner-completed-with-targets");
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else {
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                return shouldContinue;
            });

        session.setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started chain plan for player {} at ({}, {}, {}), radius={}, maxBlocks={}",
            playerUUID, origin.getX(), origin.getY(), origin.getZ(), Config.chainRadius, Config.chainMaxBlocks);
    }

    private ChainSearchContext createSearchContext(net.minecraft.world.World world, ChainSession session, BlockSeedSnapshot seedSnapshot) {
        session.getTraversalTargets().clear();
        return ChainSearchContextFactory.createBlockFloodFillContext(world, session, seedSnapshot);
    }

    private boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState) {
        return playerState.isChainKeyPressed() && ChainHarvestRules.hasEnoughDurability(player);
    }

}
