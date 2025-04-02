package club.heiqi.qz_miner.client.playerInput;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.client.AnimateMessages;
import club.heiqi.qz_miner.client.cubeRender.RenderCube;
import club.heiqi.qz_miner.client.cubeRender.RenderRegion;
import club.heiqi.qz_miner.client.cubeRender.SpaceCalculator;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.utils.Utils;
import club.heiqi.qz_miner.network.PacketIsReady;
import club.heiqi.qz_miner.network.PacketPrintResult;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector2d;
import org.joml.Vector3i;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20.glUseProgram;

@SideOnly(Side.CLIENT)
public class PlayerInput {
    public static Logger LOG = LogManager.getLogger();
    public static Minecraft mc = Minecraft.getMinecraft();
    public static ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public static Vector3i center = new Vector3i();

    public static int intervalTime = 25; // 最小发包间隔50ms
    public static int renderInterval = 500;
    public static long lastSendTime;
    public static String category = "key.categories.qz_miner";

    public static final KeyBinding switchMainMode = new KeyBinding(
        I18n.format("key.qz_miner.switchMode"), Keyboard.KEY_C, category
    );
    public static final KeyBinding switchMode = new KeyBinding(
        I18n.format("key.qz_miner.switchSubMode"), Keyboard.KEY_Z, category
    );
    public static final KeyBinding isPress = new KeyBinding(
        I18n.format("key.qz_miner.isPress"), Keyboard.KEY_GRAVE, category
    );


    public static ModeManager manager;

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        manager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        if (manager == null) return;

        if (switchMainMode.isPressed()) {
            manager.nextMainMode();
            String message = "当前主模式: " + getMainMode();
            printMessage(message);
        }
        if (switchMode.isPressed()) {
            manager.nextSubMode();
            String message = "当前子模式: " + getSubMode();
            printMessage(message);
        }
    }

    @SubscribeEvent
    public void onInputEvent(InputEvent event) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return;
        manager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        if (manager == null) return;

        boolean isPressed = isPress.getIsKeyPressed();
        if (System.currentTimeMillis() - lastSendTime < intervalTime) return;
        lastSendTime = System.currentTimeMillis();
        if (!isPressed && manager.getIsReady()) { // 如果未按下，且玩家状态为连锁就绪，关闭就绪状态
            manager.setIsReady(false);
            QzMinerNetWork.sendMessageToServer(new PacketIsReady(false));
        }
        if (isPressed && !manager.getIsReady()) { // 如果按下，且玩家状态为未就绪，就开启就绪状态
            manager.setIsReady(true);
            QzMinerNetWork.sendMessageToServer(new PacketIsReady(true));
        }
    }

    public boolean overlay1 = false;
    @SubscribeEvent
    public void OnRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        manager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        if (manager == null) {
            allPlayerStorage.clientRegister(player);
            return;
        }
        if (!Config.showTip) {
            int curShader = glGetInteger(GL_CURRENT_PROGRAM);
            glUseProgram(0);
            glPushAttrib(GL_ALL_ATTRIB_BITS);
            boolean isReady = manager.getIsReady();
            if (!isReady) {
                glColor4f(0.823f, 0.411f, 0.117f, 0.3f);
            } else {
                glColor4f(0.678f, 1.0f, 0.184f, 1.0f);
            }
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_TEXTURE_2D);
            glDisable(GL_LIGHTING);
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glPushMatrix();
            glBegin(GL_TRIANGLES);
            glVertex2i(3, event.resolution.getScaledHeight());
            glVertex2i(0, event.resolution.getScaledHeight() - 3);
            glVertex2i(0, event.resolution.getScaledHeight());
            glEnd();
            glPopMatrix();
//            glColor3f(1.0f, 1.0f, 1.0f);
            glPopAttrib();
            glUseProgram(curShader);
            return;
        }
        if (!(event.type == RenderGameOverlayEvent.ElementType.TEXT)) return; // 如果不是字体渲染阶段则跳过
        boolean isReady = manager.getIsReady();
        if (!isReady) {
            overlay1 = false;
            return;
        }

        FontRenderer fr = mc.fontRenderer;
        int screenWidth = mc.displayWidth;
        int screenHeight = mc.displayHeight;
        int scale = event.resolution.getScaleFactor();
        String tip = I18n.format("key.qz_miner.tip");

        int x = (int) (screenWidth * 0.01);
        int y = (int) (screenHeight * 0.99);

        int fontHeight = fr.FONT_HEIGHT;
        int heightHalf = (int) Math.ceil(fontHeight / 2d);
        y = y -heightHalf;
        double endX = 0.01; double endY = (double) (y-fontHeight*scale) / screenHeight;
        double startX = endX; double startY = 1.1;
        if (isReady && count > 0) {
            String counts = "["+ count+"]";
            int width = fr.getStringWidth(counts);
            width /= 2;
            fr.drawString(counts, (screenWidth/scale)/2-width, (screenHeight/scale)/2-fontHeight-5, 0xCC6622);
        }
        if (isReady && !overlay1) {
            int tipW = fr.getStringWidth(tip);
            double stringWD = (double) (tipW * scale) /screenWidth;
            Vector2d start1 = new Vector2d(startX, startY);
            Vector2d start2 = new Vector2d(startX+stringWD, startY);
            Vector2d end1 = new Vector2d(endX, endY);
            Vector2d end2 = new Vector2d(endX + stringWD, endY);
            List<Vector2d> paths1 = Arrays.asList(start1, end1, end1,start1);
            List<Vector2d> paths2 = Arrays.asList(start2, end2, end2,start2);
            List<Long> duration = Arrays.asList(1_000L, 3_000L,1_000L);
            /*AnimateMessages amTip = new AnimateMessages().register(tip, 0xFFFFFF, paths1, duration);
            AnimateMessages rdyAm = new AnimateMessages().register(ready, 0x1eff00, paths2, duration);*/
            AnimateMessages amTip = new AnimateMessages().useFunc((vec)->{
                String ready = manager.getIsReady() ? I18n.format("key.qz_miner.isReady") : I18n.format("key.qz_miner.notReady");
                int readyColor = manager.getIsReady() ? 0x1eff00 : 0xff7500;
                int xi = vec.x; int yi = vec.y-fontHeight;
                fr.drawString(tip, xi, yi, 0xFFFFFF);
                fr.drawString(ready, xi+tipW, yi, readyColor);
            }).register(paths1, duration);
            overlay1 = true;
        /*fr.drawString(tip, x, y - heightHalf, 0xFFFFFF);
        if (isReady) {
            fr.drawString(ready, x + fontWidth + 1, y - heightHalf, 0x1eff00);
        } else {
            fr.drawString(ready, x + fontWidth + 1, y - heightHalf, 0xff7500);
        }*/
        }
    }

    @SubscribeEvent
    public void onLoginClient(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                QzMinerNetWork.sendMessageToServer(new PacketPrintResult(Config.printResult));
            }
        }).start();
    }

    RenderRegion regionRender;
    public int count = 0;
    public static SpaceCalculator calculator = new SpaceCalculator(new ArrayList<>());
    public boolean inRender = false;
    @SubscribeEvent
    public void onInteract(DrawBlockHighlightEvent event) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (!Config.useRender) return;
        manager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        if (manager == null) return;

        if (regionRender == null) regionRender = new RenderRegion();
        if (!RenderCube.isInit) RenderCube.init();
        // 清空缓存
        if (!manager.getIsReady()) {
            // 清空计算器
            if (!calculator.points.isEmpty()) {
                calculator.clear();
                LOG.info("已清空计算器中缓存");
            }
            // 清空管理器
            if (!manager.renderCache.isEmpty()) {
                manager.renderCache.clear();
            }
            count = 0;
            inRender = false;
            return;
        }
        // 方块位置
        int bx = event.target.blockX;
        int by = event.target.blockY;
        int bz = event.target.blockZ;
        // 计算距离
        int dx = (int) (bx - player.posX);
        int dy = (int) (by - player.posY + player.eyeHeight);
        int dz = (int) (bz - player.posZ);

        // 渲染列表状态追踪
        if (!inRender) {
            if ((dx * dx + dy * dy + dz * dz) > 25) {
                return;
            }
            /*LOG.info("[渲染] 正在触发渲染模式");*/
            manager.proxyRender(new Vector3i(bx, by, bz));
            inRender = true;
        }
        for (Vector3i vector3i : manager.renderCache) {
            if (count >= Config.renderCount) break;
            calculator.addPoint(vector3i);
            count = calculator.points.size();
        }
        // 绘制逻辑
        int curProgram = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(0);
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glPushClientAttrib(GL_VERTEX_ARRAY);
        glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);
        glLineWidth(1f);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND); // 确保混合已启用
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glColor4d(Utils.optimizedOscillation(Config.renderFadeSpeedMultiplier, 0.3),Utils.optimizedOscillation(Config.renderFadeSpeedMultiplier, 0.5),Utils.optimizedOscillation(Config.renderFadeSpeedMultiplier),0.3);
        // 计算玩家视角偏移（同原逻辑）
        float ex = (float) (player.prevPosX + (player.posX - player.prevPosX) * event.partialTicks);
        float ey = (float) (player.prevPosY + (player.posY - player.prevPosY) * event.partialTicks);
        float ez = (float) (player.prevPosZ + (player.posZ - player.prevPosZ) * event.partialTicks);
        List<Float> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int cubeCount = 0;
        for (Map.Entry<Vector3i, SpaceCalculator.Point> vP : calculator.points.entrySet()) {
            Vector3i blockPos = vP.getKey();
            SpaceCalculator.Point point = vP.getValue();
            RenderCube.DefaceList dfList = new RenderCube.DefaceList(point.deFaces);
            RenderCube cube = RenderCube.connect.get(dfList);
            if (cube == null) continue;
            if (cube.indexes.length == 0) continue; // 跳过0顶点加速
            float x = blockPos.x + 0.5f;
            float y = blockPos.y + 0.5f;
            float z = blockPos.z + 0.5f;
            // 处理顶点
            for (int i = 0; i < RenderCube.vertices.length; i+=3) {
                float a = RenderCube.vertices[i]; a += x;
                float b = RenderCube.vertices[i+1]; b += y;
                float c = RenderCube.vertices[i+2]; c += z;
                vertices.add(a); vertices.add(b); vertices.add(c);
            }
            // 处理索引
            int indexStep = cubeCount*8;
            for (int i = 0; i < cube.indexes.length; i++) {
                int index = cube.indexes[i]; index += indexStep;
                indices.add(index);
            }

            /*glPushMatrix();

            // 通过计算偏移
            glTranslatef(x-ex,y-ey,z-ez);*/

            // 直接通过构造模型视图矩阵方式渲染
            /*Matrix4f model = new Matrix4f().identity().translate(x, y, z);
            Matrix4f view = RenderUtils.getViewMatrix(event.partialTicks);
            glMatrixMode(GL_MODELVIEW);
            RenderUtils.uploadModelView(view.mul(model, new Matrix4f()));*//*
            RenderCube.render(new RenderCube.DefaceList(point.deFaces));

            glPopMatrix();*/

            cubeCount++;
        }
        // 获取数组
        float[] verticesArray = new float[vertices.size()];
        int[] indicesArray = new int[indices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            verticesArray[i] = vertices.get(i);
        }
        for (int i = 0; i < indices.size(); i++) {
            indicesArray[i] = indices.get(i);
        }
        // 渲染
        glPushMatrix();
        glTranslatef(-ex, -ey, -ez);
        regionRender.render(verticesArray, indicesArray);

        glPopMatrix();

        glPopClientAttrib();
        glPopAttrib();
        glUseProgram(curProgram);
        /*LOG.debug("完成方块高亮绘制");*/ // 绘制结束日志
    }

    public String getMainMode() {
        return I18n.format(manager.mainMode.unLocalizedName);
    }

    public String getSubMode() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        manager = allPlayerStorage.playerStatueMap.get(player.getUniqueID());
        ModeManager.MainMode mainMode = manager.mainMode;
        switch (mainMode) {
            case RANGE_MODE -> {
                return I18n.format(manager.rangeMode.unLocalizedName);
            }
            case CHAIN_MODE -> {
                return I18n.format(manager.chainMode.unLocalizedName);
            }
        }
        return "获取当前模式时发生错误！";
    }

    public void printMessage(String message) {
        ChatComponentText text = new ChatComponentText(message);
        mc.thePlayer.addChatMessage(text);
    }

    public PlayerInput register() {
        ClientRegistry.registerKeyBinding(switchMainMode);
        ClientRegistry.registerKeyBinding(switchMode);
        ClientRegistry.registerKeyBinding(isPress);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
        return this;
    }
}
