package club.heiqi.qz_miner.MineModeSelect.AllChainMode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper;
import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 该模式通过矿物词典进行连锁挖掘
 */
public class RectangularChainMode implements MinerChain{
    public static int rangeLimit = Config.radiusLimit;
    public static int blockCountLimit = Config.blockLimit;

    /**
     * 从中心方块开始获取临近的6方块
     * 判断是否是类似块 -- 需要一套白名单系统 可配置文件
     * @param world
     * @param player
     * @param x
     * @param y
     * @param z
     * @return
     */
    @Override
    public Point[] getPointList(World world, EntityPlayer player, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        ItemStack itemBlock = new ItemStack(block);
        int[] blockOreDict = OreDictionary.getOreIDs(itemBlock);
        Point centerPoint = new Point(x, y, z);
        List<Point> pointList = new ArrayList<>();
        int curMaxX = x;
        int curMinX = x;
        int curMaxY = y;
        int curMinY = y;
        int curMaxZ = z;
        int curMinZ = z;
        Set<Point> visited = new java.util.HashSet<>();
        pointList.add(new Point(x, y, z));
        visited.add(new Point(x, y, z));
        List<Point> next = BlockMethodHelper.getSurroundPoints(x, y, z);
        Queue<Point> queue = new java.util.LinkedList<>(next);

        while(!queue.isEmpty()) {
            Point curPoint = queue.poll();
            if (visited.contains(curPoint)) continue;
            if (pointList.size() >= blockCountLimit) break;
            Block thisBlock = world.getBlock(curPoint.x, curPoint.y, curPoint.z);
            int[] thisOreDict = OreDictionary.getOreIDs(new ItemStack(thisBlock));
            if(!BlockMethodHelper.checkTwoDictIsSame(thisOreDict, blockOreDict)){
                continue;
            }
            pointList.add(curPoint);
            next = BlockMethodHelper.getSurroundPoints(curPoint.x, curPoint.y, curPoint.z);
            List<Point> needRemove = new ArrayList<>();
            for(Point point : next) {
                if(visited.contains(point)) {
                    needRemove.add(point);
                    continue;
                }
                int curX = point.x;
                int curY = point.y;
                int curZ = point.z;
                // 求xyz边界
                if(checkPoint(point, centerPoint, needRemove)) { // 如果是在边界内,更新内部边界值
                    curMaxX = Math.max(curMaxX, curX);
                    curMinX = Math.min(curMinX, curX);
                    curMaxY = Math.max(curMaxY, curY);
                    curMinY = Math.min(curMinY, curY);
                    curMaxZ = Math.max(curMaxZ, curZ);
                    curMinZ = Math.min(curMinZ, curZ);
                }
            }
            if(needRemove == next) continue;
            else next.removeAll(needRemove);
            queue.addAll(next);
        }
        return pointList.toArray(new Point[0]);
    }

    @Override
    public Supplier<Point> getPoint_supplier(World world, EntityPlayer player, Point center, int radius, int blockLimit) {
        final List<Point> cache = new ArrayList<>();
        final Set<Point> visited = new HashSet<>();
        final int[] distance = new int[]{0};
        final int[] blockCount = new int[]{0};
        cache.add(center);

        return new Supplier<Point>() {
            @Override
            public Point get() {
                try {
                    if(blockCount[0] >= blockLimit) return null;
                    if(cache.isEmpty()) return null; // 缓存为空, 认定链路结束
                    Point curPoint = cache.remove(0); // 获取到点
                    if(curPoint == null) return null;
                    if(visited.contains(curPoint)) return get();
                    visited.add(curPoint); // 标记为已访问

                    // 随挖随补
                    List<Point> surroundingPoints = BlockMethodHelper.getSurroundPointsEnhanced(world, curPoint, Config.chainRange);// 获取范围内临近的所有点
                    for(Point point : surroundingPoints) { // 如果点未访问过 检查点是否在范围内, 剔除掉超过范围的点
                        if(!visited.contains(point) && BlockMethodHelper.checkPointBlockIsValid(world, point)) {
                            cache.add(point);
                        }
                    }

                    Block curPointBlock = world.getBlock(curPoint.x, curPoint.y, curPoint.z);
                    int meta = world.getBlockMetadata(curPoint.x, curPoint.y, curPoint.z);
                    if (BlockMethodHelper.checkPointBlockIsValid(world, curPoint)
                        && curPointBlock.canHarvestBlock(player, curPointBlock.getDamageValue(world, curPoint.x, curPoint.y, curPoint.z))) {
                        blockCount[0]++;
                        return curPoint;
                    } else {
                        return get();
                    }
                } catch (Exception e) {
                    MY_LOG.LOG.error("getPoint_supplier 函数错误", e);
                    return null;
                }
//                while(true) {
//                    while(cache.isEmpty()) {
//                        Point curPoint = queue.poll();
//                        if(curPoint == null || blockCount[0] >= blockLimit) return null;
//                        if(visited.contains(curPoint)) continue;
//                        visited.add(curPoint);
//
//                        List<Point> surroundPoint = BlockMethodHelper.getSurroundPointsEnhanced(world, curPoint, Config.chainRange);
//                        for(Point point : surroundPoint) {
//                            if(!visited.contains(point) && BlockMethodHelper.checkPointBlockIsValid(world, point)) {
//                                cache.add(point);
//                                queue.add(point);
//                            }
//                        }
//                    }
//                    MY_LOG.LOG.info("cache size: {}", cache.size());
//                    Point waitRet = cache.remove(0);
//                    Block waitRetBlock = world.getBlock(waitRet.x, waitRet.y, waitRet.z);
//                    if(BlockMethodHelper.checkPointBlockIsValid(world, waitRet)
//                        && waitRetBlock.canHarvestBlock(player, world.getBlockMetadata(waitRet.x, waitRet.y, waitRet.z))
//                        && BlockMethodHelper.checkPointIsInBox(waitRet, center, radius)
//                    ) {
//                        blockCount[0]++;
//                        return waitRet;
//                    }
//                }
            }
        };
    }

    public boolean checkPoint(Point curPoint, Point centerPoint, List<Point> needRemove) {
        if(Math.abs(curPoint.x - centerPoint.x) > rangeLimit) {
            needRemove.add(curPoint);
            return false;
        }
        if(Math.abs(curPoint.y - centerPoint.y) > rangeLimit) {
            needRemove.add(curPoint);
            return false;
        }
        if(Math.abs(curPoint.z - centerPoint.z) > rangeLimit) {
            needRemove.add(curPoint);
            return false;
        }
        return true;
    }


}
