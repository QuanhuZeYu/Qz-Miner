package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Util.DistanceCalculate;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.Queue;
import java.util.ArrayList;
import java.util.function.Supplier;

import static club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper.getOutBoundOfPoint;

public class PlanarRestrictedMode implements MinerChain {
    @Override
    public Point[] getPointList(World world, EntityPlayer player, int x, int y, int z) {
        int blockCount = 0;
        int blockLimit = Config.blockLimit;
        int radius = Config.radiusLimit;

        int playerX = (int) player.posX;
        int playerY = (int) player.posY;
        int playerZ = (int) player.posZ;

        Set<Point> blockCoordinates = new HashSet<Point>(); // 防止重复添加
        Set<Point> visited = new HashSet<Point>(); // 防止重复访问
        Queue<Point> queue = new LinkedList<Point>();

        // 从中心点开始
        queue.add(new Point((int) x, (int) y, (int) z));
        visited.add(new Point((int) x, (int) y, (int) z));

        while (!queue.isEmpty() && blockCount < blockLimit) {
            Point current = queue.poll(); // 取出队列中的第一个元素
            int currentX = current.x;
            int currentY = current.y;
            int currentZ = current.z;

            // 计算当前点到中心点的距离
            double distance = DistanceCalculate.getDistance(currentX, currentY, currentZ, x, y, z);
            // 如果距离在半径范围内
            if (distance <= radius) {
                Block block = world.getBlock(currentX, currentY, currentZ);
                var isLiquid = block.getMaterial().isLiquid();
                if (block != Blocks.air && !isLiquid) {
                    blockCoordinates.add(current);
                    blockCount++;
                }

                // 添加相邻的六个方块
                for (int[] offset : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                    Point neighbor = new Point(currentX + offset[0], currentY + offset[1], currentZ + offset[2]);
                    if (currentY + offset[1] < playerY) {
                        continue;
                    }
                    if (!visited.contains(neighbor)) {
                        queue.add(neighbor);
                        visited.add(neighbor);
                    }
                }
            }
        }

        return blockCoordinates.toArray(new Point[0]);
    }

    @Override
    public Supplier<Point> getPoint_supplier(World world, EntityPlayer player, Point center, int radius, int blockLimit) {
        final int[] distance = {0};
        final List<Point> cache = new ArrayList<>();
        final int[] blockCount = {0};

        return new Supplier<Point>() {
            @Override
            public Point get() {
                if(blockCount[0] >= blockLimit) return null;
                if (distance[0] > radius) return null;
                // 如果缓存的点列表为空，尝试填充新的点
                while (cache.isEmpty()) {
                    distance[0]++;
                    getOutBoundOfPoint(cache, center, distance[0]); // 补充 cache
                }

                // 取出一个点
                Point waitRet = cache.remove(0);
                // 判断逻辑
                Block block = world.getBlock(waitRet.x, waitRet.y, waitRet.z);
                Material material = block.getMaterial();
                int meta = world.getBlockMetadata(waitRet.x, waitRet.y, waitRet.z);
                // 如果是液体或空气，跳过该点，继续尝试获取下一个点
                if (material.isLiquid() || block == Blocks.air || !block.canHarvestBlock(player, meta)) {
                    return get(); // 递归调用获取下一个有效的点
                }
                if(waitRet.y < player.posY) return get(); // 跳过低于玩家的点

                // 返回符合条件的点, 计数器+1
                blockCount[0]++;
                return waitRet;
            }
        };
    }

    @Override
    public Supplier<Point> getPoint_supplier(Point center, int radius, int blockLimit) {
        return null;
    }
}
