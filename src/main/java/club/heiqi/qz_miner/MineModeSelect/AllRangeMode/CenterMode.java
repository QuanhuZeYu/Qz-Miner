package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Util.DistanceCalculate;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import java.util.List;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.ArrayList;
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
        final int[] blockCount = new int[]{0};
        cache.add(center);

        return new Supplier<Point>() {
            /**
             * 每提取一个点，
             * @return
             */
            @Override
            public Point get() {
                try {
                    if(cache.isEmpty()) return null;
                    if(blockCount[0] >= blockLimit) return null;
                    Point curPoint = cache.remove(0);
                    if(visited.contains(curPoint)) return get();
                    Block curPointBlock = world.getBlock(curPoint.x, curPoint.y, curPoint.z);
                    visited.add(curPoint);
                    if (BlockMethodHelper.checkPointBlockIsValid(world, curPoint)
                        && curPointBlock.canHarvestBlock(player, curPointBlock.getDamageValue(world, curPoint.x, curPoint.y, curPoint.z))) {
                        checkPointInRadius(cache, curPoint, center, radius);
                        blockCount[0]++;
                        return curPoint;
                    } else {
                        checkPointInRadius(cache, curPoint, center, radius);
                        return get();
                    }
                } catch (Exception e) {
                    MY_LOG.LOG.warn("寻找点时出现错误:", e);
                    return null;
                }
            };
        };
    }

    @Override
    public Supplier<Point> getPoint_supplier(Point center, int radius, int blockLimit) {
        return null;
    }

    public void checkPointInRadius(List<Point> cache, Point curPoint, Point center, int radius) {
        List<Point> surroundPoint = BlockMethodHelper.getSurroundPoints(curPoint.x, curPoint.y, curPoint.z);
        for(Point point : surroundPoint) {
            if(BlockMethodHelper.manhattanDistance(point, center) > radius) {
                continue;
            } else {
                cache.add(point);
            }
        }
    }
}
