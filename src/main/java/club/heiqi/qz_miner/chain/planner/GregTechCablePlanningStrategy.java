package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * GT 线缆特殊模式规划策略。
 */
public class GregTechCablePlanningStrategy extends AbstractFloodFillPlanningStrategy {

    public GregTechCablePlanningStrategy() {
        super(ChainMode.SPECIAL);
    }

    @Override
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
        ChainSession session = new ChainSession(
            player.getUniqueID(),
            playerState.getSelectedMode(),
            playerState.getSelectedSubMode(),
            origin,
            1,
            0.0F,
            0.0F,
            0.0F,
            playerState.getRequestedChainRadius(),
            playerState.getRequestedChainMaxBlocks());
        startPlanningInternal(player, playerState, origin, session);
    }

    @Override
    protected boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState) {
        return player != null && playerState.isChainKeyPressed();
    }

    @Override
    protected String getRestartReason() {
        return "restart-special-cable-plan";
    }

    @Override
    protected String getStartReason() {
        return "start-special-cable-plan";
    }

    @Override
    protected String getTaskPrefix() {
        return "special-cable-plan-";
    }

    @Override
    protected String getStopReasonPrefix() {
        return "special-cable-plan-";
    }

    @Override
    protected String getPlannerReasonPrefix() {
        return "special-cable-planner-";
    }

    @Override
    protected String getLogLabel() {
        return "special-cable";
    }

    @Override
    protected boolean shouldIncludeOriginTarget() {
        return true;
    }

    @Override
    protected void logPlanCompleted(UUID playerUUID, ChainSearchContext searchContext, ConcurrentLinkedQueue<ChainTarget> queue, ChainPlayerState currentState) {
        MyMod.LOG.debug("[ChainPlanner] Special cable plan completed for player {}, confirmed={}, queuedTargets={}",
            playerUUID, searchContext.getConfirmedCount(), queue.size());
    }
}
