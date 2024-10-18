package club.heiqi.qz_miner.MineModeSelect;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import club.heiqi.qz_miner.CustomData.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class BlockMethodHelper {
    public static List<Block> getSurroundBlocks(World world, EntityPlayer player, int x, int y, int z) {
        List<Block> surroundBlock = new ArrayList<>();
        List<Point> surroundBlockPonitList = getSurroundPoints(x, y, z);
        for(Point point : surroundBlockPonitList) {
            Block block = world.getBlock(point.x, point.y, point.z);
            // 判断是否是空气方块
            if(block == Blocks.air) continue;
            surroundBlock.add(block);
        }
        return surroundBlock;
    }

    public static List<Block> removeLiquid(List<Block> blockList) {
        blockList.removeIf(block -> block.getMaterial().isLiquid());
        return blockList;
    }

    public static List<Point> getSurroundPoints(int x, int y, int z) {
        Point top = new Point(x, y+1, z);
        Point bottom = new Point(x, y-1, z);
        Point left = new Point(x-1, y, z);
        Point right = new Point(x+1, y, z);
        Point front = new Point(x, y, z+1);
        Point back = new Point(x, y, z-1);
        return new ArrayList<>(Arrays.asList(top, bottom, left, right, front, back));
    }

    public static boolean checkTwoDictIsSame(int[] dict1, int[] dict2) {
        boolean isSame = false;
        for(int thisDict : dict1) {
            for(int targetDict : dict2) {
                if(thisDict == targetDict) {
                    isSame = true;
                    break;
                }
            }
        }
        return isSame;
    }

    /**
     * 获取立方体外侧面所在所有点列表
     * @param YpXpZp 顶部右侧前方
     * @param YsXsZs 底部左侧后方
     * @return 所有外侧面点的列表
     */
    public static List<Point> getOutBoundOfPoint(Point YpXpZp, Point YsXsZs) {
        List<Point> points = new ArrayList<>();

        int minX = Math.min(YpXpZp.x, YsXsZs.x) - 1;
        int maxX = Math.max(YpXpZp.x, YsXsZs.x) + 1;
        int minY = Math.min((Math.min(YpXpZp.y, YsXsZs.y) - 1), 0);
        int maxY = Math.max((Math.max(YpXpZp.y, YsXsZs.y) + 1), 255);
        int minZ = Math.min(YpXpZp.z, YsXsZs.z) - 1;
        int maxZ = Math.max(YpXpZp.z, YsXsZs.z) + 1;

        // 添加六个面的所有边界点
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                points.add(new Point(x, y, minZ));
                points.add(new Point(x, y, maxZ));
            }
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                points.add(new Point(minX, y, z));
                points.add(new Point(maxX, y, z));
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                points.add(new Point(x, minY, z));
                points.add(new Point(x, maxY, z));
            }
        }
        return points;
    }

    /**
     * 获取立方体外侧面所在所有点列表
     *
     * @param center 中心点
     * @param radius 半径（每个方向上的偏移量）
     */
    public static void getOutBoundOfPoint(List<Point> pointList, Point center, int radius) {

        int minX = center.x - radius;
        int maxX = center.x + radius;
        int minY = center.y - radius;
        int maxY = center.y + radius;
        int minZ = center.z - radius;
        int maxZ = center.z + radius;

        // 添加六个面的所有边界点
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                // 前后面
                pointList.add(new Point(x, y, minZ));
                pointList.add(new Point(x, y, maxZ));
            }
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                // 左右面
                pointList.add(new Point(minX, y, z));
                pointList.add(new Point(maxX, y, z));
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // 上下面
                pointList.add(new Point(x, minY, z));
                pointList.add(new Point(x, maxY, z));
            }
        }
    }

    public static Supplier<Point> getOutBoundOfPointSupplier(World world, EntityPlayer player, Point center, int radius, int blockLimit) {
        final int[] distance = {0};
        final List<Point> cache = new ArrayList<>();
        final int[] blockCount = {0};

        return new Supplier<Point>() {
            @Override
            public Point get() {
                // 如果缓存的点列表为空，尝试填充新的点
                while (cache.isEmpty()) {
                    if(blockCount[0] >= blockLimit) return null;
                    if (distance[0] > radius) return null;
                    distance[0]++;
                    getOutBoundOfPoint(cache, center, distance[0]); // 补充 cache
                }

                // 取出一个点
                Point waitRet = cache.remove(0);
                Block block = world.getBlock(waitRet.x, waitRet.y, waitRet.z);
                Material material = block.getMaterial();

                // 如果是液体或空气，跳过该点，继续尝试获取下一个点
                if (material.isLiquid() || block == Blocks.air) {
                    return get(); // 递归调用获取下一个有效的点
                }

                // 返回符合条件的点, 计数器+1
                blockCount[0]++;
                return waitRet;
            }
        };
    }
}
