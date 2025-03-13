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
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.glLoadMatrix;

@SideOnly(Side.CLIENT)
public class RenderUtils {
    public static Logger LOG = LogManager.getLogger();
    public static EntityPlayer player;

    public static Matrix4f getViewMatrix() {
        Vec3 vec = player.getPosition(Minecraft.getMinecraft().timer.renderPartialTicks);
        float eyeX = (float) vec.xCoord;
        float eyeY = (float) vec.yCoord + player.eyeHeight;
        float eyeZ = (float) vec.zCoord;
        return new Matrix4f().lookAt(
            new Vector3f(eyeX, eyeY, eyeZ),
            new Vector3f(eyeX, eyeY, eyeZ).add(getLookDir()),
            new Vector3f(0,1,0)
        );
    }

    public static Vector3f getLookDir() {
        // 转换为弧度制
        float yawRad = (float) Math.toRadians(player.rotationYaw);
        float pitchRad = (float) Math.toRadians(player.rotationPitch);
        // 计算前向量
        double lookX = -Math.sin(yawRad)*Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad)*Math.cos(pitchRad);
        return new Vector3f((float) lookX, (float) lookY, (float) lookZ);
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
