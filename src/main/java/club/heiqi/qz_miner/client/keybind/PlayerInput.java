package club.heiqi.qz_miner.client.keybind;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.client.RenderUtils;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.minerModes.chainMode.BaseChainMode;
import club.heiqi.qz_miner.network.PacketIsReady;
import club.heiqi.qz_miner.network.PacketPrintResult;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
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
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        UUID uuid = player.getUniqueID();
        manager = allPlayerStorage.playerStatueMap.get(uuid);
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
        UUID uuid = player.getUniqueID();
        manager = allPlayerStorage.playerStatueMap.get(uuid);
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

    @SubscribeEvent
    public void OnRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueID();
        manager = allPlayerStorage.playerStatueMap.get(uuid);
        if (manager == null) return;
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

        FontRenderer fr = mc.fontRenderer;
        int screenWidth = mc.displayWidth;
        int screenHeight = mc.displayHeight;
        int scale = event.resolution.getScaleFactor();
        String ready = isReady ? I18n.format("key.qz_miner.isReady") : I18n.format("key.qz_miner.notReady");
        String tip = I18n.format("key.qz_miner.tip", ready);

        int x = (int) (screenWidth * 0.01);
        int y = (int) (screenHeight * 0.99);
        x = x / scale;
        y = y / scale;
        int fontWidth = fr.getStringWidth(tip);
        int fontHeight = fr.FONT_HEIGHT;
        int heightHalf = (int) Math.ceil(fontHeight / 2d);
        fr.drawString(tip, x, y - heightHalf, 0xFFFFFF);
        if (isReady) {
            fr.drawString(ready, x + fontWidth + 1, y - heightHalf, 0x1eff00);
        } else {
            fr.drawString(ready, x + fontWidth + 1, y - heightHalf, 0xff7500);
        }
        mc.mcProfiler.endSection();
    }

    @SubscribeEvent
    public void onLoginClient(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                QzMinerNetWork.sendMessageToServer(new PacketPrintResult(Config.printResult));
            }
        }).start();
    }

    public List<Vector3i> renderList = new ArrayList<>();
    @SubscribeEvent
    public void onInteract(DrawBlockHighlightEvent event) {
        if (manager == null) return;
        if (!manager.getIsReady()) {
            if (!renderList.isEmpty()) {
                LOG.info("清空渲染列表（原大小{}）", renderList.size());
                renderList = new ArrayList<>();
            }
            if (!manager.renderCache.isEmpty()) {
                LOG.info("清空管理器渲染缓存（原大小{}）", manager.renderCache.size());
                manager.renderCache = new ArrayList<>();
            }
            return;
        }
        LOG.info("已就绪，开始准备渲染高亮方块");
        EntityPlayer player = manager.player;
        int bx = event.target.blockX;
        int by = event.target.blockY;
        int bz = event.target.blockZ;
        int dx = (int) (bx - player.posX);
        int dy = (int) (by - player.posY + player.eyeHeight);
        int dz = (int) (bz - player.posZ);
        if ((dx*dx + dy*dy + dz*dz) > 25) {
            LOG.info("目标方块距离超过16格（坐标：{},{},{}）, 玩家坐标: ({}, {}, {})跳过渲染", bx, by, bz, player.posX, player.posY, player.posZ);
            return;
        }
        // 渲染列表状态追踪
        if (renderList.isEmpty()) {
            LOG.info("初始化渲染列表并添加初始位置（{},{},{}）", bx, by, bz);
            manager.proxyRender(new Vector3i(bx, by, bz));
            renderList.add(new Vector3i(0));
        } else if (!manager.renderCache.isEmpty()) {
            LOG.info("使用缓存中的渲染列表（缓存大小：{}）", manager.renderCache.size());
            renderList = new ArrayList<>(manager.renderCache);
        }
        LOG.debug("开始绘制方块高亮（数量：{}）", renderList.size()); // 绘制开始日志
        // 绘制逻辑
        glPushAttrib(GL_ALL_ATTRIB_BITS);
        glDisable(GL_TEXTURE_2D);
        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND); // 确保混合已启用
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glColor4d(1,0,0,0.3);
        for (Vector3i pos : new ArrayList<>(renderList)) {
            // 推入栈
            float x = pos.x + 0.5f;
            float y = pos.y + 0.5f;
            float z = pos.z + 0.5f;
            float ex = (float) player.posX;
            float ey = (float) player.posY + player.eyeHeight;
            float ez = (float) player.posZ;
            glPushMatrix();
            // 通过计算偏移
            glTranslatef(x-ex,y-ey,z-ez);
            // 直接通过构造模型视图矩阵方式渲染
//            Matrix4f model = new Matrix4f().identity().translate(x, y, z);
//            Matrix4f view = RenderUtils.getViewMatrix();
//            glMatrixMode(GL_MODELVIEW);
//            RenderUtils.uploadModelView(view.mul(model, new Matrix4f()));
            glBegin(GL_LINES);
            // 前面
            glVertex3f(0.5f, 0.5f, 0.5f); glVertex3f(-0.5f, 0.5f, 0.5f);
            glVertex3f(-0.5f, 0.5f, 0.5f); glVertex3f(-0.5f, -0.5f, 0.5f);
            glVertex3f(-0.5f, -0.5f, 0.5f); glVertex3f(0.5f, -0.5f, 0.5f);
            glVertex3f(0.5f, -0.5f, 0.5f); glVertex3f(0.5f, 0.5f, 0.5f);
            // 后面
            glVertex3f(0.5f, 0.5f, -0.5f); glVertex3f(-0.5f, 0.5f, -0.5f);
            glVertex3f(-0.5f, 0.5f, -0.5f); glVertex3f(-0.5f, -0.5f, -0.5f);
            glVertex3f(-0.5f, -0.5f, -0.5f); glVertex3f(0.5f, -0.5f, -0.5f);
            glVertex3f(0.5f, -0.5f, -0.5f); glVertex3f(0.5f, 0.5f, -0.5f);
            // 链接前后
            glVertex3f(0.5f, 0.5f, 0.5f); glVertex3f(0.5f, 0.5f, -0.5f);
            glVertex3f(-0.5f, 0.5f, 0.5f); glVertex3f(-0.5f, 0.5f, -0.5f);
            glVertex3f(-0.5f, -0.5f, 0.5f); glVertex3f(-0.5f, -0.5f, -0.5f);
            glVertex3f(0.5f, -0.5f, 0.5f); glVertex3f(0.5f, -0.5f, -0.5f);
            glEnd();
            glPopMatrix();
        }
        glPopAttrib();
        LOG.debug("完成方块高亮绘制"); // 绘制结束日志
    }

    public String getMainMode() {
        return I18n.format(manager.mainMode.unLocalizedName);
    }

    public String getSubMode() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        UUID uuid = player.getUniqueID();
        manager = allPlayerStorage.playerStatueMap.get(uuid);
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

    public void register() {
        ClientRegistry.registerKeyBinding(switchMainMode);
        ClientRegistry.registerKeyBinding(switchMode);
        ClientRegistry.registerKeyBinding(isPress);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
