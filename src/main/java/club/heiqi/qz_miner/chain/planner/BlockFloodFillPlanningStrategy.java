package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 当前默认的方块洪泛规划策略。
 */
public class BlockFloodFillPlanningStrategy extends AbstractFloodFillPlanningStrategy {

    public BlockFloodFillPlanningStrategy() {
        super(ChainMode.CHAIN);
    }

    @Override
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
        ChainSession session = new ChainSession(
            player.getUniqueID(),
            playerState.getSelectedMode(),
            playerState.getSelectedSubMode(),
            origin);
        startPlanningInternal(player, playerState, origin, session);
    }

    @Override
    protected boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState) {
        return playerState.isChainKeyPressed() && ChainHarvestRules.hasEnoughDurability(player);
    }

    @Override
    protected String getRestartReason() {
        return "restart-plan";
    }

    @Override
    protected String getStartReason() {
        return "start-plan";
    }

    @Override
    protected String getTaskPrefix() {
        return "chain-plan-";
    }

    @Override
    protected String getStopReasonPrefix() {
        return "plan-";
    }

    @Override
    protected String getPlannerReasonPrefix() {
        return "planner-";
    }

    @Override
    protected String getLogLabel() {
        return "chain";
    }

    @Override
    protected void logPlanCompleted(UUID playerUUID, ChainSearchContext searchContext, ConcurrentLinkedQueue<ChainTarget> queue, ChainPlayerState currentState) {
        int pendingDrops = currentState.getSession() == null ? 0 : currentState.getSession().getRuntimeState().getPendingDrops().size();
        MyMod.LOG.debug("[ChainPlanner] Plan completed for player {}, confirmed={}, queuedTargets={}, pendingDrops={}",
            playerUUID, searchContext.getConfirmedCount(), queue.size(), pendingDrops);
    }
}
