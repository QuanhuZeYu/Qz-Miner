package club.heiqi.qz_miner.minerModes.rangeMode.posFounder;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.PositionFounder;
import club.heiqi.qz_miner.util.CheckCompatibility;
import gregtech.common.blocks.BlockOresAbstract;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

public class RectangularMineralFounder extends PositionFounder {
    public int rad = 1;

    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param mode
     * @param center 被破坏方块的中心坐标
     * @param player 执行者
     */
    public RectangularMineralFounder(AbstractMode mode, Vector3i center, EntityPlayer player) {
        super(mode, center, player);
    }


    /**循环体，由父类触发*/
    @Override
    public void mainLogic() {
        if (rad >= radiusLimit) return;
        List<Vector3i> result = new ArrayList<>();
        // 1. X 轴正负方向的两个面
        for (int xSign : new int[]{-1, 1}) {
            int x = center.x + xSign * rad; // ✅ 正确计算偏移
            for (int y = center.y - rad; y <= center.y + rad; y++) {
                for (int z = center.z - rad; z <= center.z + rad; z++) {
                    if (!filter(x,y,z)) continue;
                    result.add(new Vector3i(x, y, z));
                    sendHeartbeat();
                }
            }
        }
        // 2. Y 轴正负方向的两个面
        for (int ySign : new int[]{-1, 1}) {
            int y = center.y + ySign * rad; // ✅ 正确计算偏移
            for (int x = center.x - rad; x <= center.x + rad; x++) {
                for (int z = center.z - rad; z <= center.z + rad; z++) {
                    if (!filter(x,y,z)) continue;
                    result.add(new Vector3i(x, y, z));
                    sendHeartbeat();
                }
            }
        }
        for (int zSign : new int[]{-1, 1}) {
            int z = center.z + zSign * rad; // ✅ 正确计算偏移
            for (int x = center.x - rad; x <= center.x + rad; x++) {
                for (int y = center.y - rad; y <= center.y + rad; y++) { // ✅ 修复循环变量为 y
                    if (!filter(x,y,z)) continue;
                    result.add(new Vector3i(x, y, z));
                    sendHeartbeat();
                }
            }
        }
        cache.addAll(sort(result));
        rad++;
    }

    public boolean filter(int bx, int by, int bz) {
        Block tb = world.getBlock(bx,by,bz);
        if (CheckCompatibility.isHasClass_BlockOresAbstract && tb instanceof BlockOresAbstract) return true; // 是矿石
        TileEntity tt = world.getTileEntity(bx,by,bz);
        if (CheckCompatibility.isHasClass_TileEntityOre && tt instanceof TileEntityOres) return true;
        if (tb.getUnlocalizedName().contains("ore") && !tb.getUnlocalizedName().contains("machine")) return true;
        return false;
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
