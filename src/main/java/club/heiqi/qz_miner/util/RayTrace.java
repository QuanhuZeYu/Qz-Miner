package club.heiqi.qz_miner.util;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3i;

public class RayTrace {
    public static class CapTrace {
        public int x,y,z;
        public Block block;
        public CapTrace(int x,int y,int z,Block block) {
            this.x = x;this.y=y;this.z=z;this.block=block;
        }
    }
    // 获取当前玩家视线前的方块
    @Nullable
    public static CapTrace rayTraceBlock(EntityPlayer player) {
        Vector3d pos = new Vector3d(player.posX,player.posY+player.getEyeHeight(),player.posZ);
        double cosT = Math.cos(Math.toRadians(player.rotationYaw));
        double sinT = Math.sin(Math.toRadians(player.rotationYaw));
        double cosP = Math.cos(Math.toRadians(player.rotationPitch));
        double sinP = Math.sin(Math.toRadians(player.rotationPitch));
        Vector3d lookDir = new Vector3d(-sinT*cosP,-sinP,cosT*cosP).normalize();
        // 射线追踪参数
        double step = 0.05;        // 步长（精度）
        double maxDistance = 100.0; // 最大检测距离
        // 沿视线方向逐步检测
        for (double t = 0; t < maxDistance; t += step) {
            // 计算当前检测点坐标
            Vector3d currentPos = new Vector3d(
                pos.x + lookDir.x * t,
                pos.y + lookDir.y * t,
                pos.z + lookDir.z * t
            );

            // 转换为方块坐标（向下取整）
            Vector3i blockPos = new Vector3i(
                (int) Math.floor(currentPos.x),
                (int) Math.floor(currentPos.y),
                (int) Math.floor(currentPos.z)
            );

            // 获取方块并判断
            Block block = player.worldObj.getBlock(blockPos.x, blockPos.y, blockPos.z);
            if (block != null && (block != Blocks.air && !block.getUnlocalizedName().equals("tile.railcraft.residual.heat"))) {
                return new CapTrace(blockPos.x, blockPos.y, blockPos.z, block); // 找到第一个非空气方块
            }
        }
        return null; // 未找到方块
    }
}
