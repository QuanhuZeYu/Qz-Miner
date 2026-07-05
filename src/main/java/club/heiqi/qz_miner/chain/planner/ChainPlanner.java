package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubModeTrigger;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

/**
 * 连锁规划器调度器。
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

        ChainPlayerState playerState = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
        if (!playerState.isChainKeyPressed() || playerState.isExecuting()) {
            return;
        }
        if (ChainSubModeRegistry.getTrigger(playerState.getSelectedSubMode()) != ChainSubModeTrigger.BREAK_BLOCK) {
            return;
        }

        ChainModeDefinition definition = ChainModeRegistry.getDefinition(playerState.getSelectedMode());
        if (definition == null || definition.getPlanningStrategy() == null) {
            return;
        }

        // 守 I1：BreakEvent 在服务端主线程触发；publish 仅入队不切态
        // 阶段3影子并行：保留 startPlanning，新链路仅推进状态机观测
        // 输入事件 generation 传 0 豁免代际判定
        if (MyMod.chainEventBus != null) {
            // sideHit 占位 0：Forge 1.7.10 BlockEvent.BreakEvent 不暴露命中方向（仅 metadata），右键路径才有 face
            MyMod.chainEventBus.publish(new BlockBreakObserved(
                    player.getUniqueID(), 0,
                    ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                    event.x, event.y, event.z, player.dimension, 0));
        }

        definition.getPlanningStrategy().startPlanning(player, playerState, new ChainTarget(event.x, event.y, event.z));
    }
}
