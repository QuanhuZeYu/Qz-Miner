package club.heiqi.qz_miner.minerMode.rangeMode.posFounder;

import club.heiqi.qz_miner.minerMode.AbstractMode;
import club.heiqi.qz_miner.minerMode.PositionFounderThread;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.BlockOresAbstract;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import org.joml.Vector3i;

public class RectangularMineralFounderThread extends PositionFounderThread {
    public int rad = 1;

    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param mode
     */
    public RectangularMineralFounderThread(AbstractMode mode) {
        super(mode);
    }


    /**循环体，由父类触发*/
    @Override
    public void mainLogic() {
        if (rad >= radiusLimit) return;
        // 1. X 轴正负方向的两个面
        for (int xSign : new int[]{-1, 1}) {
            int x = center.x + xSign * rad; // ✅ 正确计算偏移
            for (int y = center.y - rad; y <= center.y + rad; y++) {
                for (int z = center.z - rad; z <= center.z + rad; z++) {
                    if (Thread.currentThread().isInterrupted()) return; // 线程中断提前返回
                    filter(x,y,z);
                }
            }
        }
        // 2. Y 轴正负方向的两个面
        for (int ySign : new int[]{-1, 1}) {
            int y = center.y + ySign * rad; // ✅ 正确计算偏移
            for (int x = center.x - rad; x <= center.x + rad; x++) {
                for (int z = center.z - rad; z <= center.z + rad; z++) {
                    if (Thread.currentThread().isInterrupted()) return; // 线程中断提前返回
                    filter(x,y,z);
                }
            }
        }
        for (int zSign : new int[]{-1, 1}) {
            int z = center.z + zSign * rad; // ✅ 正确计算偏移
            for (int x = center.x - rad; x <= center.x + rad; x++) {
                for (int y = center.y - rad; y <= center.y + rad; y++) { // ✅ 修复循环变量为 y
                    if (Thread.currentThread().isInterrupted()) return; // 线程中断提前返回
                    filter(x,y,z);
                }
            }
        }
        rad++;
    }

    public void filter(int bx, int by, int bz) {
        Vector3i pos = new Vector3i(bx,by,bz);
        /*Runnable task = () -> {*/
            World world = manager.player.worldObj;
            Block tb = world.getBlock(bx,by,bz);
            if (CheckCompatibility.isHasClass_BlockOresAbstract && tb instanceof BlockOresAbstract) {
                cache.add(pos);
                return;
            }
            TileEntity tt = world.getTileEntity(bx,by,bz);
            if (CheckCompatibility.isHasClass_TileEntityOre && tt instanceof TileEntityOres) {
                cache.add(pos);
                return;
            }
            if (tb.getUnlocalizedName().contains("ore") && !tb.getUnlocalizedName().contains("machine")) {
                cache.add(pos);
                return;
            }
        /*};
        AsyncManager.pollTask(task);*/
        doWaitBool();
    }
}
