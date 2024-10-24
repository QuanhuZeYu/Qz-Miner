package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class ExcludeCrushedOreChainMode extends AbstractChainMiner{

    /**
     * 这个方法要重写并添加Event注解才有效!!!!!
     *
     * @param event
     */
    @Override
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        super.onTick(event);
    }

    /**
     * 从已经搜寻到的点中排除的逻辑
     */
    @Override
    public boolean excludeLogic(Point curPoint) {
        boolean isSimilar = BlockMethodHelper.checkPointDropIsSimilarToStack(world, player, curPoint, centerBlockDropItems);
        if(isSimilar || BlockMethodHelper.checkTwoBlockIsSameOrSimilar(BlockMethodHelper.getBlock(world, curPoint), centerBlock)) {
            return false;
        }
        return true;
    }
}
