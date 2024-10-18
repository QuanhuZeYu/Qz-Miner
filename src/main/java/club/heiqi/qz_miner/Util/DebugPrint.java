package club.heiqi.qz_miner.Util;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.CustomData.Point;
import net.minecraft.world.World;

public class DebugPrint {
    public static void printBlockList(World world, Point[] blockPointList) {
        int i = 1;
        for (Point point : blockPointList) {
            MY_LOG.LOG.info("第{}个块: {}", i, world.getBlock(point.x, point.y, point.z).getLocalizedName());
            i++;
        }
        MY_LOG.LOG.info("方块总量:{}", blockPointList.length);
    }
}
