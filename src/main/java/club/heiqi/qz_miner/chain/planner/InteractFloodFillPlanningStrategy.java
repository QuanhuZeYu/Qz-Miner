package club.heiqi.qz_miner.chain.planner;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * INTERACT 默认邻居洪泛规划策略。
 */
public class InteractFloodFillPlanningStrategy extends AbstractFloodFillPlanningStrategy {

    public InteractFloodFillPlanningStrategy() {
        super(ChainMode.INTERACT);
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
        ChainSession session = new ChainSession(
            player.getUniqueID(),
            playerState.getSelectedMode(),
            playerState.getSelectedSubMode(),
            origin,
            interactFace,
            interactHitX,
            interactHitY,
            interactHitZ);
        startPlanningInternal(player, playerState, origin, session);
    }

    @Override
    protected ChainBlockMatcher createBlockMatcher(ChainSearchContext searchContext) {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(ChainMode.INTERACT);
        return definition == null ? null : definition.createMatcher(searchContext);
    }

    @Override
    protected boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState) {
        return player != null && playerState.isChainKeyPressed();
    }

    @Override
    protected String getRestartReason() {
        return "restart-interact-plan";
    }

    @Override
    protected String getStartReason() {
        return "start-interact-plan";
    }

    @Override
    protected String getTaskPrefix() {
        return "interact-plan-";
    }

    @Override
    protected String getStopReasonPrefix() {
        return "interact-plan-";
    }

    @Override
    protected String getPlannerReasonPrefix() {
        return "interact-planner-";
    }

    @Override
    protected String getLogLabel() {
        return "interact";
    }

    @Override
    protected void logPlanCompleted(UUID playerUUID, ChainSearchContext searchContext, ConcurrentLinkedQueue<ChainTarget> queue, ChainPlayerState currentState) {
        MyMod.LOG.debug("[ChainPlanner] Interact plan completed for player {}, confirmed={}, queuedTargets={}",
            playerUUID, searchContext.getConfirmedCount(), queue.size());
    }
}
