package club.heiqi.qz_miner.client.keybind;

import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.network.PacketIsReady;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import club.heiqi.qz_miner.statueStorage.SelfStatue;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.joml.Vector3i;
import org.lwjgl.input.Keyboard;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static club.heiqi.qz_miner.Mod_Main.MODID;

@SideOnly(Side.CLIENT)
public class KeyBind {
    public static Minecraft mc = Minecraft.getMinecraft();
    public static ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    public static Vector3i center = new Vector3i();

    public static int intervalTime = 50; // 最大发包间隔50ms
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
        EntityPlayer player = mc.thePlayer;
        UUID uuid = player.getUniqueID();
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

        int x = (int) (screenWidth * 0.05);
        int y = (int) (screenHeight * 0.95);
        x = x / scale;
        y = y / scale;
        fr.drawString(tip, x, y, 0xFFFFFF);
        int fontWidth = fr.getStringWidth(tip);
        if (isReady) {
            fr.drawString(ready, x + fontWidth + 1, y, 0x1eff00);
        } else {
            fr.drawString(ready, x + fontWidth + 1, y, 0xff7500);
        }
        mc.mcProfiler.endSection();
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
