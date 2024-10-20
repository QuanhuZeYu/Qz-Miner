package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.MinerChain;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.CustomData.Point;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;

import static club.heiqi.qz_miner.MineModeSelect.BlockMethodHelper.getOutBoundOfPoint;

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
        Supplier<Point> pointSupplier = getPoint_supplier(world, player, centerPoint, radius, blockLimit);
        Point supGet; // 从supplier取出的点
        while ((supGet = pointSupplier.get()) != null) {
            blockCoordinates.add(supGet);
        }

        // 将集合转换为数组并返回
        return blockCoordinates.toArray(new Point[0]);
    }

    // 辅助方法：检查方块是否有效
    public boolean checkBlockValid(World world, Point point) {
        Block block = world.getBlock(point.x, point.y, point.z);
        boolean isLiquid = block.getMaterial().isLiquid();
        return block != Blocks.air && !isLiquid;
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
