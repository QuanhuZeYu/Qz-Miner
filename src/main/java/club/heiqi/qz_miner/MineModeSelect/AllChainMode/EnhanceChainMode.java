package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.MethodHelper.BlockMethodHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 该模式通过矿物词典进行连锁挖掘
 */
public class EnhanceChainMode extends AbstractChainMiner {

    /**
     * 从已经搜寻到的点中排除的逻辑
     */
    @Override
    public boolean excludeLogic(Point curPoint) {
        boolean isSimilar = BlockMethodHelper.checkPointDropIsSimilarToStack_IncludeCrushedOre(world, player, curPoint, centerBlockDropItems);
        if(isSimilar || BlockMethodHelper.checkTwoBlockIsSameOrSimilar(centerBlock, world.getBlock(curPoint.x, curPoint.y, curPoint.z))) {
            return false;
        }
        return true;
    }

    @Override
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        super.onTick(event);
    }



}
