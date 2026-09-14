package club.heiqi.qz_miner.chain.interaction;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * 服务端规划、客户端预览与执行期共用的右键射线数学。
 *
 * <p>姿态与视线部分不依赖玩家/世界引用，单独下沉为包级纯函数：这样「Item 右键语义固定取当前帧
 * 姿态」「眼位 = 插值坐标 + 眼高偏移」「终点 = 眼位 + 视线 × reach」这些数值契约可以在纯 JVM 里
 * 直接证伪，而不必先造出一个 {@code EntityPlayer}（1.7.10 的玩家实体构造链要求真实
 * {@code WorldServer}）。{@link #trace(EntityPlayer, double, boolean)} 只负责把它们接到真实玩家上，
 * 并保持「不持有玩家引用」的既有约束。</p>
 */
public final class InteractionRayTrace {

    /** Item 右键语义取当前帧姿态：不做部分帧插值（渲染侧的部分帧与本路径无关）。 */
    private static final float ITEM_POSE_PARTIAL_TICKS = 1.0F;

    private InteractionRayTrace() {}

    /**
     * 按 Minecraft 1.7.10 Item 右键语义，从玩家当前/上一帧姿态发射方块射线。
     *
     * @param player 玩家；方法不持有该引用
     * @param reach 当前侧权威的方块触达距离
     * @param includeLiquids 是否把液体纳入首命中；必须原样透传给世界查询
     * @return 当前首命中；玩家或世界缺失时返回 null
     */
    public static MovingObjectPosition trace(EntityPlayer player, double reach, boolean includeLiquids) {
        if (player == null || player.worldObj == null) {
            return null;
        }

        float pitch = interpolateAngle(
                player.prevRotationPitch, player.rotationPitch, ITEM_POSE_PARTIAL_TICKS);
        float yaw = interpolateAngle(
                player.prevRotationYaw, player.rotationYaw, ITEM_POSE_PARTIAL_TICKS);
        double eyeX = interpolatePosition(player.prevPosX, player.posX, ITEM_POSE_PARTIAL_TICKS);
        double eyeY = interpolatePosition(player.prevPosY, player.posY, ITEM_POSE_PARTIAL_TICKS)
                + eyeHeightOffset(
                        player.worldObj.isRemote, player.getEyeHeight(), player.getDefaultEyeHeight());
        double eyeZ = interpolatePosition(player.prevPosZ, player.posZ, ITEM_POSE_PARTIAL_TICKS);
        Vec3 eye = Vec3.createVectorHelper(eyeX, eyeY, eyeZ);
        Vec3 end = endPoint(eye, yaw, pitch, reach);
        return player.worldObj.func_147447_a(eye, end, includeLiquids, false, false);
    }

    /** 上一帧角度与当前角度的线性插值（保持 float 精度，避免射线方向漂移）。 */
    static float interpolateAngle(float previous, float current, float partialTicks) {
        return previous + (current - previous) * partialTicks;
    }

    /** 上一帧坐标与当前坐标的线性插值。 */
    static double interpolatePosition(double previous, double current, float partialTicks) {
        return previous + (current - previous) * partialTicks;
    }

    /**
     * 眼高偏移：remote 侧按 Item 姿态只取「眼高 - 默认眼高」（视角跟随手持物），其余侧取完整眼高。
     *
     * @param remote 当前侧是否客户端
     * @param eyeHeight 当前眼高
     * @param defaultEyeHeight 默认眼高
     */
    static double eyeHeightOffset(boolean remote, double eyeHeight, double defaultEyeHeight) {
        return remote ? eyeHeight - defaultEyeHeight : eyeHeight;
    }

    /**
     * Minecraft yaw/pitch 约定下的单位视线向量：yaw=0 朝 +Z、yaw=90 朝 -X、pitch=90 朝下。
     *
     * @param yaw 水平朝向（度）
     * @param pitch 俯仰（度，正值朝下）
     */
    static Vec3 lookVector(float yaw, float pitch) {
        float yawCos = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float yawSin = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float pitchCos = -MathHelper.cos(-pitch * 0.017453292F);
        float pitchSin = MathHelper.sin(-pitch * 0.017453292F);
        return Vec3.createVectorHelper(yawSin * pitchCos, pitchSin, yawCos * pitchCos);
    }

    /**
     * 从眼位沿视线前进 reach 得到终点。
     *
     * @param eye 射线起点
     * @param yaw 水平朝向（度）
     * @param pitch 俯仰（度，正值朝下）
     * @param reach 前进距离
     */
    static Vec3 endPoint(Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 look = lookVector(yaw, pitch);
        return eye.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
    }
}
