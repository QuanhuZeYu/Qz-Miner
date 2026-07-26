package club.heiqi.qz_miner.chain.interaction;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

/**
 * 服务端规划、客户端预览与执行期共用的右键射线数学。
 */
public final class InteractionRayTrace {

    private InteractionRayTrace() {}

    /**
     * 按 Minecraft 1.7.10 Item 右键语义，从玩家当前/上一帧姿态发射方块射线。
     *
     * @param player 玩家；方法不持有该引用
     * @param reach 当前侧权威的方块触达距离
     * @param includeLiquids 是否把液体纳入首命中
     * @return 当前首命中；玩家或世界缺失时返回 null
     */
    public static MovingObjectPosition trace(EntityPlayer player, double reach, boolean includeLiquids) {
        if (player == null || player.worldObj == null) {
            return null;
        }

        float partialTicks = 1.0F;
        float pitch = player.prevRotationPitch
                + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
        float yaw = player.prevRotationYaw
                + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
        double eyeX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double eyeY = player.prevPosY + (player.posY - player.prevPosY) * partialTicks
                + (player.worldObj.isRemote
                    ? player.getEyeHeight() - player.getDefaultEyeHeight()
                    : player.getEyeHeight());
        double eyeZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
        Vec3 eye = Vec3.createVectorHelper(eyeX, eyeY, eyeZ);
        float yawCos = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float yawSin = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float pitchCos = -MathHelper.cos(-pitch * 0.017453292F);
        float pitchSin = MathHelper.sin(-pitch * 0.017453292F);
        float lookX = yawSin * pitchCos;
        float lookZ = yawCos * pitchCos;
        Vec3 end = eye.addVector(lookX * reach, pitchSin * reach, lookZ * reach);
        return player.worldObj.func_147447_a(eye, end, includeLiquids, false, false);
    }
}
