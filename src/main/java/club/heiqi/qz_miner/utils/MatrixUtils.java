package club.heiqi.qz_miner.utils;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.lang.Math;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.FloatBuffer;

@SideOnly(Side.CLIENT)
public class MatrixUtils {
    public static Logger LOG = LogManager.getLogger();

    /**
     * 仅计算位移
     */
    public static Matrix4f getModelMatrix(float x, float y, float z) {
        Matrix4f modelMatrix = new Matrix4f();
        modelMatrix.identity();

        modelMatrix.translate(new Vector3f(x, y, z));
        return modelMatrix;
    }

    public static Matrix4f getViewMatrix(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;

        // 位置插值（保持不变）
        double eyeX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double eyeY = player.prevPosY + (player.posY - player.prevPosY) * partialTicks;
        double eyeZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;

        // 角度插值优化：处理360°环绕问题
        float yaw = interpolateAngle(player.prevRotationYaw, player.rotationYaw, partialTicks);
        float pitch = interpolateAngle(player.prevRotationPitch, player.rotationPitch, partialTicks);

        // 创建目标矩阵
        Matrix4f viewMatrix = new Matrix4f();

        // 使用弧度制转换（优化精度）
        float radPitch = (float) Math.toRadians(pitch);
        float radYaw = (float) Math.toRadians(yaw + 180);

        // 四元数旋转
        Quaternionf rotation = new Quaternionf()
                .rotateX(radPitch)
                .rotateY(radYaw);
        viewMatrix.rotation(rotation);

        // 应用平移
        viewMatrix.translate((float) -eyeX, (float) -eyeY, (float) -eyeZ);

        return viewMatrix;
    }

    // 处理角度环绕的插值方法
    private static float interpolateAngle(float prevAngle, float angle, float partialTicks) {
        // 计算最短路径角度差
        float delta = angle - prevAngle;
        delta = (delta + 180) % 360 - 180;  // 约束到[-180, 180]

        return prevAngle + delta * partialTicks;
    }

    // public static Matrix4f getViewMatrix(float partialTicks) {
    //     partialTicks = Math.min(Math.max(partialTicks+0.05f, 0), 1);
    //     Minecraft mc = Minecraft.getMinecraft();
    //     EntityPlayer player = mc.thePlayer;
    //
    //     // 获取玩家眼睛位置
    //     //  位置 = 上一帧位置 + (当前帧位置 - 上一帧位置) * partialTicks
    //     double eyeX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
    //     double eyeY = player.prevPosY + (player.posY - player.prevPosY) * partialTicks;
    //     double eyeZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;
    //
    //     // 获取玩家朝向
    //     float yaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * partialTicks;
    //     float pitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * partialTicks;
    //
    //     // 计算视图矩阵
    //     Matrix4f viewMatrix = new Matrix4f();
    //     viewMatrix.identity();
    //
    //     // 旋转矩阵（根据相机的偏航和俯仰）
    //     viewMatrix.rotate((float)Math.toRadians(pitch), new Vector3f(1, 0, 0));
    //     viewMatrix.rotate((float)Math.toRadians(yaw+180), new Vector3f(0, 1, 0));
    //
    //     // 平移矩阵（将世界平移到相机位置）
    //     viewMatrix.translate(new Vector3f((float)-eyeX, (float)-eyeY, (float)-eyeZ));
    //     return viewMatrix;
    // }

    public static Method getFOVModifierMethod = null;
    public static Field farPlaneDistanceField = null;
    public static Matrix4f getProjectionMatrix() {
        // 获取Minecraft实例
        Minecraft mc = Minecraft.getMinecraft();

        // 创建ScaledResolution对象
        ScaledResolution scaledResolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);

        // 获取缩放后的宽度和高度
        int scaledWidth = scaledResolution.getScaledWidth();
        int scaledHeight = scaledResolution.getScaledHeight();

        float fov = 45f;
        try {
            if (getFOVModifierMethod == null) {
                getFOVModifierMethod = mc.entityRenderer.getClass()
                        .getDeclaredMethod("getFOVModifier", float.class, boolean.class);
                getFOVModifierMethod.setAccessible(true);
            }

            fov = (float) getFOVModifierMethod.invoke(mc.entityRenderer, mc.gameSettings.fovSetting, true);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            LOG.error("获取FOV失败:👇\n", e);
            throw new RuntimeException("获取FOV失败:👇\n", e);
        }

        float farPlane = 1000f;
        try {
            if (farPlaneDistanceField == null) {
                farPlaneDistanceField = mc.entityRenderer.getClass().getDeclaredField("farPlaneDistance");
                farPlaneDistanceField.setAccessible(true);
            }

            farPlane = farPlaneDistanceField.getFloat(mc.entityRenderer);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.error("获取farPlaneDistance失败:👇\n", e);
            throw new RuntimeException("获取farPlaneDistance失败:👇\n", e);
        }

        Matrix4f projectionMatrix = new Matrix4f();
        projectionMatrix.identity();

        projectionMatrix.perspective(
                (float)Math.toRadians(fov),
                (float)scaledWidth / (float)scaledHeight,
                0.05f, farPlane
        );
        return projectionMatrix;
    }

    public static final FloatBuffer floatBuffer = BufferUtils.createFloatBuffer(16);
    public static final Matrix4f modelView = new Matrix4f();
    public static final Matrix4f projection = new Matrix4f();

    public static Matrix4f getModelViewByOriginal() {return modelView;}
    public static Matrix4f getProjectionByOriginal() {return projection;}

    public static Vector3f getCameraPos(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;

        // 位置插值（保持不变）
        double eyeX = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
        double eyeY = player.prevPosY + (player.posY - player.prevPosY) * partialTicks;
        double eyeZ = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;

        return new Vector3f((float) eyeX, (float) eyeY, (float) eyeZ);
    }
}
