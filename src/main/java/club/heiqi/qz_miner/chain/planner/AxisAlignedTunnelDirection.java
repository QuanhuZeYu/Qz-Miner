package club.heiqi.qz_miner.chain.planner;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;

/**
 * 轴对齐隧道方向工具。
 */
public final class AxisAlignedTunnelDirection {

    private AxisAlignedTunnelDirection() {}

    /**
     * 按玩家视角解析最近的轴对齐朝向。
     *
     * @param player 玩家
     * @return Minecraft 方块面编号
     */
    public static int resolveFace(EntityPlayer player) {
        if (player == null) {
            return 1;
        }

        Vec3 lookVec = player.getLookVec();
        if (lookVec == null) {
            return 1;
        }

        double absX = Math.abs(lookVec.xCoord);
        double absY = Math.abs(lookVec.yCoord);
        double absZ = Math.abs(lookVec.zCoord);

        if (absY >= absX && absY >= absZ) {
            return lookVec.yCoord >= 0.0D ? 1 : 0;
        }

        if (absZ >= absX) {
            return lookVec.zCoord >= 0.0D ? 3 : 2;
        }

        return lookVec.xCoord >= 0.0D ? 5 : 4;
    }
}
