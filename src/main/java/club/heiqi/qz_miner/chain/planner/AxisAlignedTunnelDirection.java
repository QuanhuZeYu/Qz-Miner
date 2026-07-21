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

        return resolveFace(lookVec.xCoord, lookVec.yCoord, lookVec.zCoord);
    }

    /**
     * 按向量解析最近的轴对齐朝向；平局保持 Y、Z、X 优先级。
     *
     * @param x X 分量
     * @param y Y 分量
     * @param z Z 分量
     * @return Minecraft 方块面编号
     */
    public static int resolveFace(double x, double y, double z) {
        double absX = Math.abs(x);
        double absY = Math.abs(y);
        double absZ = Math.abs(z);

        if (absY >= absX && absY >= absZ) {
            return y >= 0.0D ? 1 : 0;
        }

        if (absZ >= absX) {
            return z >= 0.0D ? 3 : 2;
        }

        return x >= 0.0D ? 5 : 4;
    }

    /** @return face 是否属于 Minecraft 六面编号 */
    public static boolean isValidFace(int face) {
        return face >= 0 && face <= 5;
    }

    /**
     * 返回方块面的反向面。
     *
     * @param face 外法线面
     * @return 反向面；非法值返回 -1
     */
    public static int oppositeFace(int face) {
        switch (face) {
            case 0: return 1;
            case 1: return 0;
            case 2: return 3;
            case 3: return 2;
            case 4: return 5;
            case 5: return 4;
            default: return -1;
        }
    }

    /**
     * HIT_FACE 使用外法线的反向；非法 face 严格回退已冻结 look。
     */
    public static int resolveHitFaceOrLook(int hitFace, int lookFace) {
        int opposite = oppositeFace(hitFace);
        return opposite >= 0 ? opposite : normalizeFace(lookFace);
    }

    /** @return 合法 face 原值；非法值回退 +Y */
    public static int normalizeFace(int face) {
        return isValidFace(face) ? face : 1;
    }
}
