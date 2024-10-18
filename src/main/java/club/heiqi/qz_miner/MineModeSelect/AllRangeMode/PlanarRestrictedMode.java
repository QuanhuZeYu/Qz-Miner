package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.Util.DistanceCalculate;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

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
}
