package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import club.heiqi.qz_miner.MineModeSelect.MethodHelper.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.MethodHelper.PointFonder.PointFonder_Rectangular;
import club.heiqi.qz_miner.MineModeSelect.PointMethodHelper;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import net.minecraft.entity.player.EntityPlayerMP;

import static club.heiqi.qz_miner.MY_LOG.logger;

public abstract class AbstractChainMiner extends AbstractMiner {

    @Override
    public boolean addPointFonder() {
        pointFonder = new PointFonder_Rectangular(center);
        return true;
    }

    @Override
    public void taskEndPhase() {
        if(!AllPlayerStatue.getStatue(player.getUniqueID()).minerIsOpen || currentState == TaskState.IDLE) {
//            printMessage("当前挖掘任务被取消 或者已经完成");
            complete();
        }else {
            long lastTime = System.currentTimeMillis();
            while (!cache.isEmpty()) {
                Point point = cache.remove(0);
                boolean inRadius = PointMethodHelper.checkPointIsInBox(point, center, Config.radiusLimit);
                if(!inRadius) {
//                    printMessage("2.当前点不在BOX内");
                    continue;
                }
                boolean isValid = BlockMethodHelper.checkPointBlockIsValid(world, point);
                if(!isValid) {
//                    printMessage("2.当前点是空气或者液体");
                    continue;
                }
                // 获取对应点的方块是否可以收获
                boolean canHarvest = world.getBlock(point.x, point.y, point.z).canHarvestBlock(player, world.getBlockMetadata(point.x, point.y, point.z));
                /*logger.info("当前尝试挖掘的方块: {Name: {}, MetaID: {}, canHarvest: {}}",
                    world.getBlock(point.x, point.y, point.z).getLocalizedName(),
                    world.getBlockMetadata(point.x, point.y, point.z),
                    canHarvest);*/
                if(!canHarvest) {
                    /*logger.info("2.当前点无法收获");*/
                    continue;
                }
                if(excludeLogic(point)) continue;

                BlockMethodHelper.tryHarvestBlock(world, (EntityPlayerMP) player, point);
                blockCount++;
                cacheMined.add(point);

                if(checkTimeOut(lastTime)) break;
                if(blockCount > Config.blockLimit) {
                    complete();
                    return;
                }
            }
            currentState = TaskState.Start; // 继续开始任务
        }
    }
}
