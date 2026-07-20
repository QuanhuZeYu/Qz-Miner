package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

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
        // sideHit 占位 0：Forge 1.7.10 BlockEvent.BreakEvent 不暴露命中方向（仅 metadata），右键路径才有 face
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
                    event.x, event.y, event.z, player.dimension, 0,
                    event.block, event.blockMetadata, seedTileIdentity));
            // 起点方块掉落捕获：原版 tryHarvestBlock 在本 tick 同步 removeBlock+触发 HarvestDropsEvent，
            // 早于 collector 窗口打开（onPlanCompleted 时 setExecutionWindow(true)，下 tick drain）；
            // 用一次 armed 标志让 collector 在守卫 isExecuting()=false 时也收起点方块掉落进 buffer。
            // 同 tick 戳校验兜底跨 tick 陈旧；consumed 一次即清零防 onPlanCompleted 后误判。
            playerState.armSeedDropCapture(ChainTickSource.currentServerTick());
        }
    }
}
