package club.heiqi.qz_miner.minerModes.utils;

import net.minecraft.entity.EntityLivingBase;
import org.joml.Vector3f;

public class Utils {
    public static Vector3f getLookDir(EntityLivingBase entity) {
        // 转换为弧度制
        float yawRad = (float) Math.toRadians(entity.rotationYaw);
        float pitchRad = (float) Math.toRadians(entity.rotationPitch);
        // 计算前向量
        double lookX = -Math.sin(yawRad)*Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad)*Math.cos(pitchRad);
        return new Vector3f((float) lookX, (float) lookY, (float) lookZ);
    }
}
