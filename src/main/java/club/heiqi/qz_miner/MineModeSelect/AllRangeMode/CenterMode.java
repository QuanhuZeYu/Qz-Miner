package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Util.DistanceCalculate;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Supplier;

public class CenterMode implements MinerChain {
    public Point[] getPointList(World world, EntityPlayer player, int x, int y, int z) {
        int blockLimit = Config.blockLimit;
        int radius = Config.radiusLimit;

        Set<Point> blockCoordinates = new HashSet<Point>(); // 防止重复添加
        Set<Point> visited = new HashSet<Point>(); // 防止重复访问
        Queue<Point> queue = new LinkedList<Point>();

        // 从中心点开始
        queue.add(new Point((int) x, (int) y, (int) z));
        visited.add(new Point((int) x, (int) y, (int) z));

        // 广度优先搜索
        while (!queue.isEmpty() && blockCoordinates.size() < blockLimit) {
            Point current = queue.poll();
            visited.add(current);
            int currentX = current.x;
            int currentY = current.y;
            int currentZ = current.z;

            // 计算当前点与中心的距离
            double distance = DistanceCalculate.getDistance(currentX, currentY, currentZ, x, y, z);

            // 如果距离在半径范围内
            if (distance <= radius) {
                Block block = world.getBlock(currentX, currentY, currentZ);
                var isLiquid = block.getMaterial().isLiquid();
                if (block != Blocks.air && !isLiquid) {
                    blockCoordinates.add(current);
                }

                // 添加相邻的六个方块
                List<Point> waitAdd= BlockMethodHelper.getSurroundPoints(currentX, currentY, currentZ);
                queue.addAll(waitAdd);
            }
        }

        // 将坐标列表转换为数组并返回
        return blockCoordinates.toArray(new Point[0]);
    }



    @Override
    public Supplier<Point> getPoint_supplier(World world, EntityPlayer player, Point center, int radius, int blockLimit) {
        final List<Point> cache = new ArrayList<>();  // 确认要挖掘的点
        final Set<Point> visited = new HashSet<>();  // 已经访问过的点
        final Queue<Point> queue = new LinkedList<>(); // 需要待访问周围的点
        final int[] blockCount = new int[]{0};
        queue.add(center);

        return new Supplier<Point>() {
            @Override
            public Point get() {
                if(blockCount[0] >= blockLimit) return null;
                if(queue.isEmpty()) return null;
                if(cache.isEmpty()) {
                    BlockMethodHelper.getOutLine(world, cache, center, queue, visited, radius);
                }
                // 二次校验,校验成功添加计数器
                if(BlockMethodHelper.checkPointBlockIsValid(world, cache.get(0)) && world.getBlock(cache.get(0).x, cache.get(0).y, cache.get(0).z).canHarvestBlock(player, world.getBlockMetadata(cache.get(0).x, cache.get(0).y, cache.get(0).z))){
                    blockCount[0]++;
                    return cache.remove(0);
                } else {
                    return get();
                }
            }
        };
    }
}
