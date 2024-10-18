package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Supplier;

public class CenterRectangularMode implements MinerChain {
    @Override
    public Point[] getPointList(World world, EntityPlayer player, int x, int y, int z) {
        int blockLimit = Config.blockLimit;
        int radius = Config.radiusLimit;
        int initSize = (radius*2+1)*(radius*2+1)*(radius*2+1);
        List<Point> blockCoordinates = new ArrayList<>(initSize);

        // 从中心点开始
        Point centerPoint = new Point(x, y, z);
        blockCoordinates.add(centerPoint);
        Supplier<Point> pointSupplier = BlockMethodHelper.getOutBoundOfPointSupplier(world, player, centerPoint, radius, blockLimit);
        Point supGet; // 从supplier取出的点
        while ((supGet = pointSupplier.get()) != null) {
            blockCoordinates.add(supGet);
        }

        // 将集合转换为数组并返回
        return blockCoordinates.toArray(new Point[0]);
    }

    // 添加方块到集合中并检查是否达到上限
    public boolean addBlockIfValid(World world, Set<Point> blockCoordinates, Point point, int blockLimit) {
        if (blockCoordinates.size() >= blockLimit) {
            return true;
        }
        if (checkBlockValid(world, point)) {
            blockCoordinates.add(point);
        }
        return false;
    }

    // 辅助方法：检查方块是否有效
    public boolean checkBlockValid(World world, Point point) {
        Block block = world.getBlock(point.x, point.y, point.z);
        boolean isLiquid = block.getMaterial().isLiquid();
        return block != Blocks.air && !isLiquid;
    }
}
