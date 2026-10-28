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

/** 观察服务端原版破坏，在动作窗口冻结种子与方向并发布 BlockBreakObserved，交状态机启动规划。 */
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

        // 主线程发布动作观测；输入代际填零，由状态机按当前阶段处理。
        int frozenFace = 0;
        frozenFace = freezeBreakFace(playerState, playerState.getSelectedSubMode(),
                player.dimension, event.x, event.y, event.z,
                AxisAlignedTunnelDirection.resolveFace(player));
        if (MyMod.chainEventBus != null) {
            long serverRoundId = MyMod.autoToolSwapRoundService == null ? 0L
                    : MyMod.autoToolSwapRoundService.currentRoundId(player.getUniqueID(), player);
            // BreakEvent 仍在服务端主线程、原版 removeBlock 前：在此读取一次 live TE 并立即纯值化。
            // 纯值 token 随事件传给 worker，live TileEntity 引用留在此动作窗口。
            TileIdentityToken seedTileIdentity;
            try {
                TileEntity seedTileEntity = player.worldObj.getTileEntity(event.x, event.y, event.z);
                seedTileIdentity = CompatAdapters.captureTileIdentity(seedTileEntity);
            } catch (RuntimeException | LinkageError failure) {
                seedTileIdentity = TileIdentityToken.unresolved();
            }
            // BreakEvent 早于同步 removeBlock，先传递 event.block/meta，供后续 drain 保留破坏前种子身份。
            MyMod.chainEventBus.publish(new BlockBreakObserved(
                    player.getUniqueID(), serverRoundId, 0,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    event.x, event.y, event.z, player.dimension, frozenFace,
                    event.block, event.blockMetadata, seedTileIdentity));
            // 起点 HarvestDropsEvent 在本 tick 同步发生，先 arm 一次同 tick 捕获，覆盖稍后规划登记前的窗口。
            playerState.armSeedDropCapture(ChainTickSource.currentServerTick());
        }
    }

    /** 隧道按已接受方向来源消费 pending 并冻结 face，其余子模式返回缺省面。 */
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
