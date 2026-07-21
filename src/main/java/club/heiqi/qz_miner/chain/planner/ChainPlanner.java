package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * 连锁规划器调度器。
 *
 * <p>阶段8：旧链路 {@code startPlanning} 调用已删除，仅 publish {@link BlockBreakObserved}
 * 走新链路 T4 破坏观测入口（状态机统一推进 ARMED→PLANNING）。</p>
 */
public class ChainPlanner {

    public ChainPlanner() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 在服务端主线程记录普通 AREA_TUNNEL 左键外法线，供随后 BreakEvent 精确一次消费。
     */
    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent event) {
        if (event == null || event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK
                || !(event.entityPlayer instanceof EntityPlayerMP)
                || event.entityPlayer instanceof FakePlayer || event.entityPlayer.worldObj == null
                || event.entityPlayer.worldObj.isRemote || MyMod.chainStateService == null) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.entityPlayer;
        ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
        if (!state.isChainKeyPressed() || state.isExecuting()
                || state.getSelectedSubMode() != ChainSubMode.AREA_TUNNEL) {
            return;
        }
        state.recordPendingTunnelHit(player.dimension, event.x, event.y, event.z, event.face);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null || !(event.getPlayer() instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.getPlayer();
        if (player instanceof FakePlayer || MyMod.chainStateService == null) {
            return;
        }

        final ChainPlayerState playerState = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
        if (!playerState.isChainKeyPressed() || playerState.isExecuting()) {
            return;
        }
        if (ChainSubModeRegistry.getTrigger(playerState.getSelectedSubMode()) != ChainSubModeTrigger.BREAK_BLOCK) {
            return;
        }

        // 守 I1：BreakEvent 在服务端主线程触发；publish 仅入队不切态
        // 阶段8：旧 startPlanning 已删，仅 publish 走新链路 T4 破坏观测入口
        // 输入事件 generation 传 0 豁免代际判定
        int frozenFace = 0;
        frozenFace = freezeBreakFace(playerState, playerState.getSelectedSubMode(),
                player.dimension, event.x, event.y, event.z,
                AxisAlignedTunnelDirection.resolveFace(player));
        if (MyMod.chainEventBus != null) {
            long serverRoundId = MyMod.autoToolSwapRoundService == null ? 0L
                    : MyMod.autoToolSwapRoundService.currentRoundId(player.getUniqueID(), player);
            // BreakEvent 仍在服务端主线程、原版 removeBlock 前：在此读取一次 live TE 并立即纯值化。
            // token 不含 TileEntity/World/NBT/Class/坐标，可随事件安全传播到 worker。
            TileIdentityToken seedTileIdentity;
            try {
                TileEntity seedTileEntity = player.worldObj.getTileEntity(event.x, event.y, event.z);
                seedTileIdentity = CompatAdapters.captureTileIdentity(seedTileEntity);
            } catch (RuntimeException | LinkageError failure) {
                seedTileIdentity = TileIdentityToken.unresolved();
            }
            // 破坏时刻捕获种子方块 + metadata：BlockEvent.BreakEvent 在 tryHarvestBlock 同步 removeBlock 之前触发，
            // 但 drainer 推迟到下一 tick START drain，届时方块已成空气，WorldBlockSeedResolver 会读空 null。
            // 故 publish 时把 event.block / event.blockMetadata 透传给 BlockBreakObserved，
            // 由 ChainPlanningEventBridge.onPlanStarted 优先用事件携带 seed 构造种子，跳过 resolver。
            MyMod.chainEventBus.publish(new BlockBreakObserved(
                    player.getUniqueID(), serverRoundId, 0,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    event.x, event.y, event.z, player.dimension, frozenFace,
                    event.block, event.blockMetadata, seedTileIdentity));
            // 起点方块掉落捕获：原版 tryHarvestBlock 在本 tick 同步 removeBlock+触发 HarvestDropsEvent，
            // 早于 collector 窗口打开（onPlanCompleted 时 setExecutionWindow(true)，下 tick drain）；
            // 用一次 armed 标志让 collector 在守卫 isExecuting()=false 时也收起点方块掉落进 buffer。
            // 同 tick 戳校验兜底跨 tick 陈旧；consumed 一次即清零防 onPlanCompleted 后误判。
            playerState.armSeedDropCapture(ChainTickSource.currentServerTick());
        }
    }

    /** 纯状态冻结边界：非隧道不读取/消费 pending，隧道按 accepted source 决定具体 face。 */
    static int freezeBreakFace(ChainPlayerState state, ChainSubMode subMode,
            int dimensionId, int x, int y, int z, int lookFace) {
        if (state == null || subMode != ChainSubMode.AREA_TUNNEL) {
            return 0;
        }
        if (state.getAcceptedTunnelDirectionSource() == TunnelDirectionSource.HIT_FACE) {
            return state.consumePendingTunnelFace(dimensionId, x, y, z, lookFace);
        }
        state.clearPendingTunnelHit();
        return AxisAlignedTunnelDirection.normalizeFace(lookFace);
    }
}
