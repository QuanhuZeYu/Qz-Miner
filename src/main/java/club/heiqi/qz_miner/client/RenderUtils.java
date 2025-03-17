package club.heiqi.qz_miner.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.glLoadMatrix;
import static org.lwjgl.opengl.GL11.glVertex3f;

@SideOnly(Side.CLIENT)
public class RenderUtils {
    public static Logger LOG = LogManager.getLogger();
    public static EntityPlayer player;
    public static final Matrix4f viewMatrix = new Matrix4f();

    public static Matrix4f getViewMatrix(float pT) {
        /*float pT = Minecraft.getMinecraft().timer.renderPartialTicks;*/
        Vector3d cameraPos = new Vector3d(
            player.prevPosX + ((player.posX - player.prevPosX)*pT),
            player.prevPosY + ((player.posY - player.prevPosY)*pT) + player.getEyeHeight(),
            player.prevPosZ + ((player.posZ - player.prevPosZ)*pT)
        );
        Vector3f camPos = new Vector3f((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);

        // 插值旋转计算（处理角度环绕）
        double interpolatedYaw = interpolateRotation(player.prevRotationYaw, player.rotationYaw, pT);
        double interpolatedPitch = interpolateRotation(player.prevRotationPitch, player.rotationPitch, pT);
        // 转为弧度
        double yaw = Math.toRadians(interpolatedYaw);
        double pitch = Math.toRadians(interpolatedPitch);

        // 根据Yaw和Pitch计算方向向量
        double x = -(Math.sin(yaw) * Math.cos(pitch));
        double y = -Math.sin(pitch);
        double z = (Math.cos(yaw) * Math.cos(pitch));
        Vector3f front = new Vector3f((float) x, (float) y, (float) z).normalize().negate();
        Vector3f target = new Vector3f(camPos).add(front);
        viewMatrix.identity()
            .translate(camPos)
            .rotateY((float) -yaw)
            .rotateX((float) pitch)
            .scale(-1,1,-1)
            .invert();
        return viewMatrix;

        /*return new Matrix4f().lookAt(
            new Vector3f(eyeX, eyeY, eyeZ),
            new Vector3f(eyeX, eyeY, eyeZ).add(getLookDir(pT)),
            new Vector3f(0,1,0)
        );*/
    }

    public static Vector3d getLookDir(float pT) {
        /*float pT = Minecraft.getMinecraft().timer.renderPartialTicks;*/
        // 插值计算平滑后的旋转角度
        double interpolatedYaw = player.prevRotationYaw + (player.rotationYaw - player.prevRotationYaw) * pT;
        double interpolatedPitch = player.prevRotationPitch + (player.rotationPitch - player.prevRotationPitch) * pT;

        // 处理角度环绕问题（例如从 -180 到 180 的跳变）
        interpolatedYaw = interpolatedYaw % 360;
        if (interpolatedYaw < 0) interpolatedYaw += 360;

        // 转换为弧度制
        double yawRad = Math.toRadians(interpolatedYaw);
        double pitchRad = Math.toRadians(interpolatedPitch);

        // 计算前向量
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        return new Vector3d(lookX, lookY, lookZ).normalize();
    }

    /**
     * 角度插值
     * @param prev 前一个角度
     * @param current 后一个角度
     * @param partialTicks 插值因子
     * @return 插值角度
     */
    private static float interpolateRotation(float prev, float current, float partialTicks) {
        float delta = current - prev;
        delta = (delta + 180) % 360 - 180; // 处理-180~180范围内的最短路径
        return prev + delta * partialTicks;
    }



    public static final FloatBuffer MATRIX_BUFFER = BufferUtils.createFloatBuffer(16);
    public static void uploadModelView(Matrix4f matrix4f) {
        matrix4f.get(MATRIX_BUFFER);
        MATRIX_BUFFER.rewind();
        glLoadMatrix(MATRIX_BUFFER);
    }







    @SubscribeEvent
    public void renderGetPlayer(PlayerEvent.PlayerLoggedInEvent event) {
        player = event.player;
    }
    public static void register() {
        RenderUtils t = new RenderUtils();
        FMLCommonHandler.instance().bus().register(t);
        MinecraftForge.EVENT_BUS.register(t);
    }
}
