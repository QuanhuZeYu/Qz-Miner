package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
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

    private final List<ChainPlanningStrategy> planningStrategies = new ArrayList<ChainPlanningStrategy>();

    public ChainPlanner() {
        planningStrategies.add(new BlockFloodFillPlanningStrategy());
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

        ChainPlanningStrategy strategy = getPlanningStrategy(playerState);
        if (strategy == null) {
            return;
        }

        strategy.startPlanning(player, playerState, new ChainTarget(event.x, event.y, event.z));
    }

    private ChainPlanningStrategy getPlanningStrategy(ChainPlayerState playerState) {
        for (ChainPlanningStrategy strategy : planningStrategies) {
            if (strategy.supports(playerState.getSelectedMode())) {
                return strategy;
            }
        }
        return null;
    }
}
