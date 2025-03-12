package club.heiqi.qz_miner.minerModes.rangeMode.posFounder;

import club.heiqi.qz_miner.minerModes.AbstractMode;
import club.heiqi.qz_miner.minerModes.PositionFounder;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;

public class RectangularFounder extends PositionFounder {
    public int rad = 1;
    /**
     * 构造函数准备执行搜索前的准备工作
     *
     * @param center 被破坏方块的中心坐标
     * @param player
     */
    public RectangularFounder(AbstractMode mode, Vector3i center, EntityPlayer player) {
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
                    result.add(new Vector3i(x, y, z));
                    sendHeartbeat();
                }
            }
        }
        for (int zSign : new int[]{-1, 1}) {
            int z = center.z + zSign * rad; // ✅ 正确计算偏移
            for (int x = center.x - rad; x <= center.x + rad; x++) {
                for (int y = center.y - rad; y <= center.y + rad; y++) { // ✅ 修复循环变量为 y
                    result.add(new Vector3i(x, y, z));
                    sendHeartbeat();
                }
            }
        }
        cache.addAll(sort(result));
        rad++;
    }

    public long sendTime = System.nanoTime();
    @Override
    public void sendHeartbeat() {
        if (System.nanoTime() - sendTime <= 5_000_000) return;
        sendTime = System.nanoTime();
        super.sendHeartbeat();
    }
}
