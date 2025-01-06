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
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Mouse;
import ru.timeconqueror.lootgames.utils.future.WorldExt;

import java.awt.*;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20.glUseProgram;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

import static club.heiqi.qz_miner.MY_LOG.LOG;

@SideOnly(Side.CLIENT)
public class RenderSelect {
    public static int counter = 0;
    public static int sameTimes = 0;
    public static int beforeShowTime = 30;
    public static float lineWidth = Config.renderLineWidth;
    public static Vector3i center = new Vector3i(-1000, -1000, -1000);
    public static volatile Vector3f color = new Vector3f(1, 1, 1);
    public static int taskLimit = 10;
    @Nullable
    public static PositionFounder positionFounder;
    public static Set<Vector3i> cached = new HashSet<>(40960);
    public static ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public static Minecraft mc = Minecraft.getMinecraft();
    public static Vector3d camPos = new Vector3d();
    public static int vao;
    public static int verticesVbo;
    public static int ebo;
    public static float[] vertices = {
        1,1,1,
        1,1,0,
        1,0,1,
        1,0,0,
        0,1,1,
        0,1,0,
        0,0,1,
        0,0,0
    };
    public static int[] indices = {
        0,1, 1,3, 3,2, 2,0,
        6,7, 7,5, 5,4, 4,6,
        2,6, 4,0, 7,3, 1,5
    };
    public static int[] up = {0,1, 1,5, 5,4, 4,0};
    public static int[] down = {3,2, 7,3, 6,7, 2,6};
    public static int[] right = {2,6, 4,0, 4,6, 2,0};
    public static int[] back = {6,7, 7,5, 5,4, 4,6};
    public static int[] front = {0,1, 1,3, 3,2, 2,0};
    public static int[] left = {1,5, 7,5, 7,3, 1,3};
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
    public void onRenderWorldLastEvent(RenderWorldLastEvent event) {
        updateCampos();
    }

//    public void readInfo(Vector3i pos) {
//        Block block = mc.theWorld.getBlock(pos.x, pos.y, pos.z);
//        TileEntity tileEntity = mc.theWorld.getTileEntity(pos.x, pos.y, pos.z);
//        LOG.info("Block: " + block + " TileEntity: " + tileEntity);
//    }

    @SubscribeEvent
    public void onDrawBlockHighlightEvent(DrawBlockHighlightEvent event) {
        if (!Config.useRender) return;
        ModeManager modeManager = SelfStatue.modeManager;
        if (SelfStatue.modeManager.getIsReady() && !modeManager.isRunning.get()) {
            readConfig();
            Vector3i curCenter = new Vector3i(event.target.blockX, event.target.blockY, event.target.blockZ);
            Vector3d playerPos = new Vector3d(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
//            readInfo(curCenter);
            if (playerPos.distanceSquared(new Vector3d(curCenter)) > (Config.renderDistance * Config.renderDistance)) {
                return;
            }
            if (curCenter != null && !center.equals(curCenter)) {
                center = curCenter;
                sameTimes = 0;
            } else if (center.equals(curCenter)) {
                sameTimes++;
                if (sameTimes == beforeShowTime) {
                    cached.clear();
                    if (positionFounder != null && positionFounder.thread != null) positionFounder.thread.interrupt();
                    positionFounder = modeManager.getPositionFounder(curCenter, mc.thePlayer, lock);
//                    positionFounder.run();
                    if (positionFounder != null) {
                        QzMinerThreadPool.pool.submit(positionFounder);
                    }
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
                if (positionFounder != null) {
                    Vector3i pos = positionFounder.cache.poll(5, TimeUnit.MILLISECONDS);
                    if (pos != null)
                        cached.add(pos);
                } else {
                    ModeManager modeManager = SelfStatue.modeManager;
                    positionFounder = modeManager.getPositionFounder(center, mc.thePlayer, lock);
                }
            } catch (InterruptedException e) {
                LOG.warn("线程异常");
            } catch (Exception e) {
                LOG.warn("异常: {}", e.toString());
            }
        }
        // 持续渲染点，每次执行只允许运行2.5ms
        int curProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(0);
        Iterator<Vector3i> iterator = cached.iterator();
        while (System.currentTimeMillis() - timer < taskLimit + Config.renderTime && iterator.hasNext()) {
            Vector3i pos = iterator.next();
            renderBlock(pos);
        }
        glUseProgram(curProgram);
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
        // 完整ebo
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

    public void updateCampos() {
        camPos = new Vector3d(mc.thePlayer.posX, mc.thePlayer.posY - mc.thePlayer.getEyeHeight() + mc.thePlayer.eyeHeight, mc.thePlayer.posZ);
    }

    public static void renderBlock(Vector3i pos) {
        if (!vaoIsInit) initVao();
        int ebo;
        if (Config.cullRender) {
            int[] indices = RenderSelect.calculateNeighbor(pos);
            if (indices.length < 2) return;
            ebo = registerEBO(indices);
        } else {
            ebo = RenderSelect.ebo;
        }

        Vector3d translate = new Vector3d(pos.x - camPos.x, pos.y - camPos.y, pos.z - camPos.z);
        glPushMatrix();
        glMatrixMode(GL_MODELVIEW);
        glTranslated(translate.x, translate.y, translate.z);
        glLineWidth(lineWidth);
        glColor3f(color.x, color.y, color.z);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);

        glEnableClientState(GL_VERTEX_ARRAY);
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        glBindVertexArray(vao);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glDrawElements(GL_LINES, indices.length, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
        glDisableClientState(GL_VERTEX_ARRAY);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_TEXTURE_2D);
        glDisable(GL_BLEND);
        glPopMatrix();
    }

    public static int[] calculateNeighbor(Vector3i pos) {
        int[] indices = RenderSelect.indices;
        Vector3i up = new Vector3i(pos.x, pos.y + 1, pos.z);
        if (cached.contains(up)) {
            indices = RenderSelect.indexSubtract(indices, RenderSelect.up);
        }
        Vector3i down = new Vector3i(pos.x, pos.y - 1, pos.z);
        if (cached.contains(down)) {
            indices = RenderSelect.indexSubtract(indices, RenderSelect.down);
        }
        Vector3i left = new Vector3i(pos.x, pos.y, pos.z - 1);
        if (cached.contains(left)) {
            indices = RenderSelect.indexSubtract(indices, RenderSelect.left);
        }
        Vector3i right = new Vector3i(pos.x, pos.y, pos.z + 1);
        if (cached.contains(right)) {
            indices = RenderSelect.indexSubtract(indices, RenderSelect.right);
        }
        Vector3i front = new Vector3i(pos.x + 1, pos.y, pos.z);
        if (cached.contains(front)) {
            indices = RenderSelect.indexSubtract(indices, RenderSelect.front);
        }
        Vector3i back = new Vector3i(pos.x - 1, pos.y, pos.z);
        if (cached.contains(back)) {
            indices = RenderSelect.indexSubtract(indices, RenderSelect.back);
        }
        return indices;
    }

    public static int registerEBO(int[] indices) {
        int ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        IntBuffer buffer2 = BufferUtils.createIntBuffer(indices.length);
        buffer2.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, buffer2, GL_STATIC_DRAW);
        return ebo;
    }

    public static void readConfig() {
        if (lineWidth != Config.renderLineWidth) lineWidth = Config.renderLineWidth;
    }

    /**
     * 渲染边框剔除重合
     * @param original
     * @param subtract
     * @return
     */
    public static int[] indexSubtract(int[] original, int[] subtract) {
        // 将 subtract 数组的每对元素存入集合（忽略顺序）
        Set<String> subtractPairs = new HashSet<>();
        for (int i = 0; i < subtract.length; i += 2) {
            int[] pair = new int[]{subtract[i], subtract[i + 1]};
            Arrays.sort(pair); // 忽略顺序
            subtractPairs.add(Arrays.toString(pair));
        }

        // 遍历 original 数组，保留未在集合中出现的对
        List<Integer> resultList = new ArrayList<>();
        for (int i = 0; i < original.length; i += 2) {
            int[] pair = new int[]{original[i], original[i + 1]};
            Arrays.sort(pair); // 忽略顺序
            if (!subtractPairs.contains(Arrays.toString(pair))) {
                resultList.add(original[i]);
                resultList.add(original[i + 1]);
            }
        }

        // 将结果转换为数组并返回
        int[] result = new int[resultList.size()];
        for (int i = 0; i < resultList.size(); i++) {
            result[i] = resultList.get(i);
        }
        return result;
    }
}
