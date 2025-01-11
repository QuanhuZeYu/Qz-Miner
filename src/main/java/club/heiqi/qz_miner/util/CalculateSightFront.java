package club.heiqi.qz_miner.util;

import club.heiqi.qz_miner.Config;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Vector3f;

public class CalculateSightFront {
    public static Vector3f calculatePos(EntityPlayer player) {
        Vector3f playerPos = new Vector3f((float) player.posX, (float) player.posY + player.eyeHeight, (float) player.posZ);
        // 计算玩家视线方向
        float pitch = (float) Math.toRadians(player.rotationPitch);
        float yaw = (float) Math.toRadians(player.rotationYaw + 90);

        float vx = (float) (Math.cos(pitch) * Math.cos(yaw));
        float vy = (float) -Math.sin(pitch);
        float vz = (float) (Math.cos(pitch) * Math.sin(yaw));
        // 视线方向的单位向量
        Vector3f direction = new Vector3f(vx, vy, vz).normalize();
        Vector3f dropPos = new Vector3f(direction).mul(Config.dropDistance).add(playerPos);
        return dropPos;
    }
}
