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

    @Override
    public Supplier<Point> getPoint_supplier(Point center, int radius, int blockLimit) {
        return null;
    }

    /**
     * 从已经搜寻到的点中排除的逻辑
     *
     * @param world
     * @param player
     * @param checkP
     * @param visited
     * @param ret
     * @param curPoints
     */
    @Override
    public int excludeLogic(World world, EntityPlayer player, Point checkP, Set<Block> visited, List<Point> ret, int curPoints) {
        Point point = null;
        for(Block vistedBlock : visited) {
            boolean dropIsSimilar = BlockMethodHelper.checkPointDropIsSimilarToStack(world, player, checkP, droppedItems);
            if(dropIsSimilar || BlockMethodHelper.checkTwoBlockIsSameOrSimilar(vistedBlock, world.getBlock(checkP.x, checkP.y, checkP.z))) {
                ret.add(checkP);
                point = checkP;
                curPoints++;
                break;
            }
        }
        if(point != null) visited.add(BlockMethodHelper.getBlock(world, point));
        return curPoints;
    }
}
