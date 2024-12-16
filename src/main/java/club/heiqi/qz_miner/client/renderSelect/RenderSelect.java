package club.heiqi.qz_miner.client.renderSelect;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.PositionFounder;
import club.heiqi.qz_miner.statueStorage.SelfStatue;
import club.heiqi.qz_miner.threadPool.QzMinerThreadPool;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.BufferUtils;

import java.awt.*;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

import static club.heiqi.qz_miner.MY_LOG.logger;

@SideOnly(Side.CLIENT)
public class RenderSelect {
    public static int counter = 0;
    public static int sameTimes = 0;
    public static int beforeShowTime = 30;
    public static float lineWidth = Config.renderLineWidth;
    public static Vector3i center = new Vector3i(-1000, -1000, -1000);
    public static volatile Vector3f color = new Vector3f(1, 1, 1);
    public static int taskLimit = 10;
    public static PositionFounder positionFounder;
    public static Set<Vector3i> cached = new HashSet<>(40960);
    public static ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public static Minecraft mc = Minecraft.getMinecraft();
    public static int vao;
    public static int verticesVbo;
    public static int ebo;
    public static float[] vertices = {
        -0.5f, -0.5f, -0.5f, // 0
        0.5f, -0.5f, -0.5f, // 1
        0.5f,  0.5f, -0.5f, // 2
        -0.5f,  0.5f, -0.5f, // 3
        -0.5f, -0.5f,  0.5f, // 4
        0.5f, -0.5f,  0.5f, // 5
        0.5f,  0.5f,  0.5f, // 6
        -0.5f,  0.5f,  0.5f  // 7
    };
    public static int[] indices = {
        0, 1, // 边 0-1
        1, 2, // 边 1-2
        2, 3, // 边 2-3
        3, 0, // 边 3-0
        4, 5, // 边 4-5
        5, 6, // 边 5-6
        6, 7, // 边 6-7
        7, 4, // 边 7-4
        0, 4, // 边 0-4
        1, 5, // 边 1-5
        2, 6, // 边 2-6
        3, 7  // 边 3-7
    };
    public static boolean vaoIsInit = false;

    public static void register(RenderSelect renderSelect) {
        MinecraftForge.EVENT_BUS.register(renderSelect);
        FMLCommonHandler.instance().bus().register(renderSelect);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (SelfStatue.modeManager.getIsReady()) {
            updateColor();
        }
    }

    @SubscribeEvent
    public void onDrawBlockHighlightEvent(DrawBlockHighlightEvent event) {
        if (SelfStatue.modeManager.getIsReady()) {
            readConfig();
            Vector3i curCenter = new Vector3i(event.target.blockX, event.target.blockY, event.target.blockZ);
            Vector3d playerPos = new Vector3d(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            if (playerPos.distanceSquared(new Vector3d(curCenter)) > 16) {
                return;
            }
            if (curCenter != null && !center.equals(curCenter)) {
                center = curCenter;
                sameTimes = 0;
            } else if (center.equals(curCenter)){
                sameTimes++;
                if (sameTimes == beforeShowTime) {
                    ModeManager modeManager = SelfStatue.modeManager;
                    cached.clear();
                    if (positionFounder != null) positionFounder.thread.interrupt();
                    positionFounder = modeManager.getPositionFounder(curCenter, mc.thePlayer, lock);
//                    positionFounder.run();
                    QzMinerThreadPool.pool.submit(positionFounder);
                }
                if (sameTimes > beforeShowTime) {
                    enduringAddCacheAndRender();
                }
            }
        } else {
            sameTimes = 0;
            if (positionFounder != null) {
                if (positionFounder.thread != null) positionFounder.thread.interrupt();
                positionFounder = null;
            }
        }
    }

    public void enduringAddCacheAndRender() {
        long timer = System.currentTimeMillis();
        // 持续获取点，每次执行任务只允许运行5ms
        while (System.currentTimeMillis() - timer < taskLimit && cached.size() < 4096) {
            try {
                Vector3i pos = positionFounder.cache.poll(5, TimeUnit.MILLISECONDS);
                if (pos != null)
                    cached.add(pos);
            } catch (InterruptedException e) {
                logger.warn("线程异常");
            }
        }
        // 持续渲染点，每次执行只允许运行10ms
        Iterator<Vector3i> iterator = cached.iterator();
        while (System.currentTimeMillis() - timer < taskLimit * 2L && iterator.hasNext()) {
            Vector3i pos = iterator.next();
            renderBlock(pos);
        }
    }

    /*@SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        if (SelfStatue.modeManager.getIsReady()) {
            Vector3i center = getPlayerLookAtBlock();
            Vector3d playerPos = new Vector3d(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
            if (center == null) {
                return;
            }
            if (playerPos.distanceSquared(new Vector3d(center)) > 16) {
                return;
            }
            renderBlock(center);
        }
    }*/

    public static void initVao() {
        int curProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(0);
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        verticesVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, verticesVbo);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_STATIC_DRAW);
        glVertexPointer(3, GL_FLOAT, 0, 0);
        glEnableClientState(GL_VERTEX_ARRAY);

        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer buffer2 = BufferUtils.createIntBuffer(indices.length);
        buffer2.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, buffer2, GL_STATIC_DRAW);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glUseProgram(curProgram);
        vaoIsInit = true;
    }

    public static void updateColor() {
        if (counter > Integer.MAX_VALUE - 500) counter = 0;
        counter++;
        double phase = counter / Config.renderFadeSpeedMultiplier;
        // 基于时间构造平滑过渡的色相
        float hue = (float) (Math.sin(phase) + 1) / 2;

        // 保持饱和度和亮度为最大值
        float saturation = 1.0f; // 饱和度
        float brightness = 1.0f; // 亮度

        // 将 HSV 转换为 RGB
        Color color = Color.getHSBColor(hue, saturation, brightness);

        // 获取 RGB 值
        float r = (float) color.getRed() / 255;
        float g = (float) color.getGreen() / 255;
        float b = (float) color.getBlue() / 255;
        RenderSelect.color.set(r, g, b);
    }

    public static void renderBlock(Vector3i pos) {
        if (!vaoIsInit) initVao();
        int curProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(0);
        Vector3d camPos = new Vector3d(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.eyeHeight, mc.thePlayer.posZ);
        Vector3d translate = new Vector3d(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
        glPushMatrix();
        glMatrixMode(GL_MODELVIEW);
        glTranslated(translate.x + 0.5f, translate.y + 0.5f, translate.z + 0.5f);
        glLineWidth(lineWidth);
        glColor3f(color.x, color.y, color.z);
        glScalef(1.01f, 1.01f, 1.01f);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);

        /*double x1 = 0, y1 = 0, z1 = 0;
        double x2 = 1, y2 = 1, z2 = 1;*/

        glEnableClientState(GL_VERTEX_ARRAY);
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        glBindVertexArray(vao);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glDrawElements(GL_LINES, indices.length, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glDisableClientState(GL_VERTEX_ARRAY);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        /*glBegin(GL_LINES);
        glVertex3d(x1, y1, z1); glVertex3d(x2, y1, z1);
        glVertex3d(x1, y1, z2); glVertex3d(x2, y1, z2);
        glVertex3d(x1, y1, z1); glVertex3d(x1, y1, z2);
        glVertex3d(x2, y1, z1); glVertex3d(x2, y1, z2);
        glVertex3d(x1, y2, z1); glVertex3d(x2, y2, z1);
        glVertex3d(x1, y2, z2); glVertex3d(x2, y2, z2);
        glVertex3d(x1, y2, z1); glVertex3d(x1, y2, z2);
        glVertex3d(x2, y2, z1); glVertex3d(x2, y2, z2);

        glVertex3d(x1, y1, z1); glVertex3d(x1, y2, z1);
        glVertex3d(x2, y1, z1); glVertex3d(x2, y2, z1);
        glVertex3d(x1, y1, z2); glVertex3d(x1, y2, z2);
        glVertex3d(x2, y1, z2); glVertex3d(x2, y2, z2);
        glEnd();*/

        /*Tessellator tess = Tessellator.instance;

        tess.startDrawing(GL_LINES);

        tess.addVertex(x1, y1, z1); tess.addVertex(x2, y1, z1);
        tess.addVertex(x1, y1, z2); tess.addVertex(x2, y1, z2);
        tess.addVertex(x1, y1, z1); tess.addVertex(x1, y1, z2);
        tess.addVertex(x2, y1, z1); tess.addVertex(x2, y1, z2);
        tess.addVertex(x1, y2, z1); tess.addVertex(x2, y2, z1);
        tess.addVertex(x1, y2, z2); tess.addVertex(x2, y2, z2);
        tess.addVertex(x1, y2, z1); tess.addVertex(x1, y2, z2);
        tess.addVertex(x2, y2, z1); tess.addVertex(x2, y2, z2);

        tess.addVertex(x1, y1, z1); tess.addVertex(x1, y2, z1);
        tess.addVertex(x2, y1, z1); tess.addVertex(x2, y2, z1);
        tess.addVertex(x1, y1, z2); tess.addVertex(x1, y2, z2);
        tess.addVertex(x2, y1, z2); tess.addVertex(x2, y2, z2);

        tess.draw();*/

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);
        glPopMatrix();

        glUseProgram(curProgram);
    }

    public static Vector3i getPlayerLookAtBlock() {
        if (mc.thePlayer == null || mc.theWorld == null) {
            return null; // 玩家或世界为空
        }
        float reachDistance = mc.playerController.getBlockReachDistance();

        // 获取玩家视线起点和方向
        Vector3f eyePosition = new Vector3f((float) mc.thePlayer.posX, (float) (mc.thePlayer.posY + mc.thePlayer.getEyeHeight()), (float) mc.thePlayer.posZ);
        float pitch = (float) Math.toRadians(mc.thePlayer.rotationPitch);
        float yaw = (float) Math.toRadians(mc.thePlayer.rotationYaw);
        Vector3f lookDirection = new Vector3f(
            (float) (-Math.sin(yaw) * Math.cos(pitch)),
            (float) -Math.sin(pitch),
            (float) (Math.cos(yaw) * Math.cos(pitch))
        ).normalize();
        // 设置步进参数
        float stepSize = 0.1f;
        Vector3f curPos = new Vector3f(eyePosition);
        for (float step = 0; step < reachDistance; step += stepSize) {
            // 当前射线位置 = 起点 + 方向 * 步进距离
            curPos.set(eyePosition).add(lookDirection.mul(step, new Vector3f()));
            // 将浮点坐标转换为方块坐标
            Vector3i blockPos = new Vector3i(
                (int) Math.floor(curPos.x()),
                (int) Math.floor(curPos.y()),
                (int) Math.floor(curPos.z()));
            // 检测是否是方块
            if (!mc.theWorld.isAirBlock(blockPos.x, blockPos.y, blockPos.z)) {
                return blockPos;
            }
        }
        return null;
    }

    public static void readConfig() {
        if (lineWidth != Config.renderLineWidth) lineWidth = Config.renderLineWidth;
    }
}
