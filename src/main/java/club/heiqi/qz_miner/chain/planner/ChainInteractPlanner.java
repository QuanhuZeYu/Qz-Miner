package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.RightClickObserved;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * INTERACT 模式右键触发规划器。
 *
 * <p>阶段8：旧链路 {@code startPlanning} 调用已删除，仅 publish {@link RightClickObserved}
 * 走新链路 T4 右键观测入口（状态机统一推进 ARMED→PLANNING）。</p>
 */
public class ChainInteractPlanner {

    private static final double INTERACT_REACH_DISTANCE = 5.0D;

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

        // RIGHT_CLICK_BLOCK 发布前是 seed 世界事实的最后存活窗口：先冻结 block/meta/TE token，
        // 后续 drain/规划不得用已经被本次右键改变的 live world 覆盖。
        FrozenInteractSeed frozenSeed = freezeInteractSeed(player, event);
        HitOffset hitOffset = resolveHitOffset(player, event);
        // 守 I1：PlayerInteractEvent 在服务端主线程触发；publish 仅入队不切态
        // 阶段8：旧 startPlanning 已删，仅 publish 走新链路 T4 右键观测入口
        // 输入事件 generation 传 0 豁免代际判定；命中偏移携带是 T4 扩右键的根因
        if (MyMod.chainEventBus != null) {
            long serverRoundId = MyMod.autoToolSwapRoundService == null ? 0L
                    : MyMod.autoToolSwapRoundService.currentRoundId(player.getUniqueID(), player);
            MyMod.chainEventBus.publish(new RightClickObserved(
                    player.getUniqueID(), serverRoundId, 0,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    event.x, event.y, event.z, player.dimension,
                    normalizeFace(event.face),
                    hitOffset.hitX, hitOffset.hitY, hitOffset.hitZ,
                    frozenSeed.block, frozenSeed.metadata, frozenSeed.tileIdentity));
        }
    }

    /**
     * 在原服务端右键事件窗口冻结规划种子；任一世界读取异常都保留 UNRESOLVED，禁止跨 tick 猜测。
     */
    private FrozenInteractSeed freezeInteractSeed(EntityPlayerMP player, PlayerInteractEvent event) {
        Block seedBlock = Blocks.air;
        int seedMeta = 0;
        TileIdentityToken seedTileIdentity = TileIdentityToken.unresolved();
        try {
            Block observedBlock = player.worldObj.getBlock(event.x, event.y, event.z);
            int observedMeta = player.worldObj.getBlockMetadata(event.x, event.y, event.z);
            TileEntity observedTileEntity = player.worldObj.getTileEntity(event.x, event.y, event.z);
            TileIdentityToken observedTileIdentity = CompatAdapters.captureTileIdentity(observedTileEntity);

            seedBlock = observedBlock == null ? Blocks.air : observedBlock;
            seedMeta = Math.max(0, observedMeta);
            seedTileIdentity = observedBlock == null || observedBlock == Blocks.air || observedMeta < 0
                    ? TileIdentityToken.unresolved() : observedTileIdentity;
        } catch (RuntimeException | LinkageError failure) {
            seedTileIdentity = TileIdentityToken.unresolved();
        }
        return new FrozenInteractSeed(seedBlock, seedMeta, seedTileIdentity);
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
        Vec3 eyePosition = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 lookVec = player.getLookVec();
        if (lookVec == null) {
            return createFaceFallback(normalizeFace(event.face));
        }

        Vec3 reachPosition = eyePosition.addVector(
            lookVec.xCoord * INTERACT_REACH_DISTANCE,
            lookVec.yCoord * INTERACT_REACH_DISTANCE,
            lookVec.zCoord * INTERACT_REACH_DISTANCE);
        MovingObjectPosition movingObjectPosition = player.worldObj.func_147447_a(eyePosition, reachPosition, false, false, true);
        if (movingObjectPosition == null || movingObjectPosition.hitVec == null) {
            return createFaceFallback(normalizeFace(event.face));
        }
        if (movingObjectPosition.blockX != event.x || movingObjectPosition.blockY != event.y || movingObjectPosition.blockZ != event.z) {
            return createFaceFallback(normalizeFace(event.face));
        }

        Vec3 hitVec = movingObjectPosition.hitVec;
        return new HitOffset(
            normalizeHit(event.x, hitVec.xCoord),
            normalizeHit(event.y, hitVec.yCoord),
            normalizeHit(event.z, hitVec.zCoord));
    }

    /**
     * 在射线结果不可用时，回退到点击面的中心点。
     *
     * @param face 点击面
     * @return 对应面的中心偏移
     */
    private HitOffset createFaceFallback(int face) {
        switch (face) {
            case 0:
                return new HitOffset(0.5F, 0.0F, 0.5F);
            case 1:
                return new HitOffset(0.5F, 1.0F, 0.5F);
            case 2:
                return new HitOffset(0.5F, 0.5F, 0.0F);
            case 3:
                return new HitOffset(0.5F, 0.5F, 1.0F);
            case 4:
                return new HitOffset(0.0F, 0.5F, 0.5F);
            case 5:
                return new HitOffset(1.0F, 0.5F, 0.5F);
            default:
                return HitOffset.ZERO;
        }
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

    /** 原右键事件窗口冻结的纯值规划种子。 */
    private static final class FrozenInteractSeed {

        private final Block block;
        private final int metadata;
        private final TileIdentityToken tileIdentity;

        private FrozenInteractSeed(Block block, int metadata, TileIdentityToken tileIdentity) {
            this.block = block;
            this.metadata = metadata;
            this.tileIdentity = tileIdentity == null ? TileIdentityToken.unresolved() : tileIdentity;
        }
    }
}
