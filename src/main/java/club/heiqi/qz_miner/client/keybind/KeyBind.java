package club.heiqi.qz_miner.client.keybind;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.network.PacketIsReady;
import club.heiqi.qz_miner.network.PacketPrintResult;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import club.heiqi.qz_miner.statueStorage.SelfStatue;
import com.cleanroommc.modularui.utils.fakeworld.RenderWorld;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Vector3i;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.Mod_Main.MODID;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20.glUseProgram;

@SideOnly(Side.CLIENT)
public class KeyBind {
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

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        ModeManager manager = SelfStatue.modeManager;
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
        ModeManager modeManager = SelfStatue.modeManager;
        boolean isPressed = isPress.getIsKeyPressed();
        if (System.currentTimeMillis() - lastSendTime < intervalTime) return;
        lastSendTime = System.currentTimeMillis();
        if (!isPressed && modeManager.getIsReady()) { // 如果未按下，且玩家状态为连锁就绪，关闭就绪状态
            modeManager.setIsReady(false);
            QzMinerNetWork.sendMessageToServer(new PacketIsReady(false));
        }
        if (isPressed && !modeManager.getIsReady()) { // 如果按下，且玩家状态为未就绪，就开启就绪状态
            modeManager.setIsReady(true);
            QzMinerNetWork.sendMessageToServer(new PacketIsReady(true));
        }
    }

    @SubscribeEvent
    public void OnRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if (!Config.showTip) {
            int curShader = glGetInteger(GL_CURRENT_PROGRAM);
            glUseProgram(0);
            glPushAttrib(GL_ALL_ATTRIB_BITS);
            boolean isReady = SelfStatue.modeManager.getIsReady();
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
        mc.mcProfiler.startSection(MODID + "_tip");
        ModeManager manager = SelfStatue.modeManager;
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
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
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

    public String getMainMode() {
        EntityPlayer player = mc.thePlayer;
        UUID uuid = player.getUniqueID();
        ModeManager modeManager = SelfStatue.modeManager;
        return I18n.format(modeManager.mainMode.unLocalizedName);
    }

    public String getSubMode() {
        EntityPlayer player = mc.thePlayer;
        UUID uuid = player.getUniqueID();
        ModeManager modeManager = SelfStatue.modeManager;
        ModeManager.MainMode mainMode = modeManager.mainMode;
        switch (mainMode) {
            case RANGE_MODE -> {
                return I18n.format(modeManager.rangeMode.unLocalizedName);
            }
            case CHAIN_MODE -> {
                return I18n.format(modeManager.chainMode.unLocalizedName);
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
