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

    // 预计算采样表（静态初始化保证只计算一次）
    private static final int TABLE_SIZE = 1024;
    private static final double[] SAMPLE_TABLE = new double[TABLE_SIZE];
    static {
        // 初始化阶段预计算正弦平方值
        for (int i = 0; i < TABLE_SIZE; i++) {
            double phase = (double)i / TABLE_SIZE;
            SAMPLE_TABLE[i] = Math.pow(Math.sin(Math.PI * phase), 2);
        }
    }
    public static double optimizedOscillation(long millis, double cyclesPerSecond) {
        // 计算总相位（范围：0~∞）
        double totalPhase = (millis / 1000.0) * cyclesPerSecond;
        // 提取小数部分获得周期相位（范围：0~1）
        double phase = totalPhase - Math.floor(totalPhase);
        // 计算查表位置（带小数用于插值）
        double pos = phase * TABLE_SIZE;
        int index = (int) Math.floor(pos);
        double fraction = pos - index;
        // 循环取模确保不越界
        index %= TABLE_SIZE;
        int nextIndex = (index + 1) % TABLE_SIZE;
        // 线性插值提升平滑度
        return SAMPLE_TABLE[index] * (1 - fraction) + SAMPLE_TABLE[nextIndex] * fraction;
    }
}
