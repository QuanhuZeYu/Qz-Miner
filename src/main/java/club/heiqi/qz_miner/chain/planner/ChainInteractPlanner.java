package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * INTERACT 模式右键触发规划器。
 */
public class ChainInteractPlanner {

    public ChainInteractPlanner() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 在服务端右键方块时触发 INTERACT 连锁规划。
     *
     * @param event 玩家交互事件
     */
    @SubscribeEvent
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.entityPlayer == null || !(event.entityPlayer instanceof EntityPlayerMP)) {
            return;
        }
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.entityPlayer;
        if (player instanceof FakePlayer || MyMod.chainStateService == null) {
            return;
        }

        ChainPlayerState playerState = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
        if (!playerState.isChainKeyPressed() || playerState.isExecuting()) {
            return;
        }
        if (ChainSubModeRegistry.getTrigger(playerState.getSelectedSubMode()) != ChainSubModeTrigger.RIGHT_CLICK_BLOCK) {
            return;
        }

        ChainModeDefinition definition = ChainModeRegistry.getDefinition(playerState.getSelectedMode());
        if (definition == null || definition.getPlanningStrategy() == null) {
            return;
        }

        ChainTarget origin = new ChainTarget(event.x, event.y, event.z);
        HitOffset hitOffset = resolveHitOffset(player, event);
        if (definition.getPlanningStrategy() instanceof InteractFloodFillPlanningStrategy) {
            ((InteractFloodFillPlanningStrategy) definition.getPlanningStrategy()).startPlanning(
                player,
                playerState,
                origin,
                normalizeFace(event.face),
                hitOffset.hitX,
                hitOffset.hitY,
                hitOffset.hitZ);
            return;
        }
        definition.getPlanningStrategy().startPlanning(player, playerState, origin);
    }

    /**
     * 规范化点击面，避免异常值影响交互执行。
     *
     * @param face 原始点击面
     * @return 合法点击面
     */
    private int normalizeFace(int face) {
        return face < 0 || face > 5 ? 1 : face;
    }

    /**
     * 将世界坐标命中点换算为方块内偏移，并约束到合法范围。
     *
     * @param blockCoord 方块坐标
     * @param hitCoord 世界命中坐标
     * @return 0 到 1 之间的命中偏移
     */
    private float normalizeHit(int blockCoord, double hitCoord) {
        float localCoord = (float) (hitCoord - blockCoord);
        if (localCoord < 0.0F) {
            return 0.0F;
        }
        if (localCoord > 1.0F) {
            return 1.0F;
        }
        return localCoord;
    }

    /**
     * 通过服务端射线结果恢复右键命中点。
     *
     * @param player 服务端玩家
     * @param event 交互事件
     * @return 命中点偏移
     */
    private HitOffset resolveHitOffset(EntityPlayerMP player, PlayerInteractEvent event) {
        MovingObjectPosition movingObjectPosition = player.rayTrace(5.0D, 1.0F);
        if (movingObjectPosition == null || movingObjectPosition.hitVec == null) {
            return HitOffset.ZERO;
        }

        Vec3 hitVec = movingObjectPosition.hitVec;
        return new HitOffset(
            normalizeHit(event.x, hitVec.xCoord),
            normalizeHit(event.y, hitVec.yCoord),
            normalizeHit(event.z, hitVec.zCoord));
    }

    /**
     * 交互命中点偏移快照。
     */
    private static final class HitOffset {

        private static final HitOffset ZERO = new HitOffset(0.0F, 0.0F, 0.0F);

        private final float hitX;
        private final float hitY;
        private final float hitZ;

        private HitOffset(float hitX, float hitY, float hitZ) {
            this.hitX = hitX;
            this.hitY = hitY;
            this.hitZ = hitZ;
        }
    }
}
