package club.heiqi.qz_miner.KeyBind;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MOD_INFO;

import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import club.heiqi.qz_miner.network.PacketIsHold;
import club.heiqi.qz_miner.network.Qz_MinerSimpleNetwork;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;
import net.minecraft.client.settings.KeyBinding;

import java.util.UUID;

@SideOnly(Side.CLIENT)
public class MinerKeyBind {
    public static Minecraft mc = Minecraft.getMinecraft();

    public static int intervalTime = 50;  // 发包间隔必须大于50ms
    public static long lastSendTime = System.currentTimeMillis();

    private static final KeyBinding switchMode = new KeyBinding(
        I18n.format("key."+ MOD_INFO.MODID +".switchMode"), Keyboard.KEY_G, ("key.categories."+ MOD_INFO.MODID)
    );
    private static final KeyBinding isPress = new KeyBinding(
        I18n.format("key."+ MOD_INFO.MODID +".holdOn"), Keyboard.KEY_GRAVE, ("key.categories."+ MOD_INFO.MODID)
    );
    private static final KeyBinding chainModeSwitch = new KeyBinding(
        I18n.format("key."+ MOD_INFO.MODID +".chainModeSwitch"), Keyboard.KEY_C, ("key.categories."+ MOD_INFO.MODID)
    );

    public static void registry() {
        ClientRegistry.registerKeyBinding(switchMode);
        ClientRegistry.registerKeyBinding(isPress);
        ClientRegistry.registerKeyBinding(chainModeSwitch);
        MinecraftForge.EVENT_BUS.register(new MinerKeyBind());
        FMLCommonHandler.instance().bus().register(new MinerKeyBind());
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        UUID uuid = Minecraft.getMinecraft().thePlayer.getUniqueID();
        if(uuid == null) {
            MY_LOG.logger.warn("UUID为空, 忽略按键事件");
            return;
        }
        if(chainModeSwitch.isPressed()) {
            AllPlayerStatue.getStatue(uuid).nextMainMode();
        }
        if(switchMode.isPressed()) {
            AllPlayerStatue.getStatue(uuid).nextMode(event);
        }
    }

    @SubscribeEvent
    public void onInputEvent(InputEvent event) {
        long curTime = System.currentTimeMillis();
        if(curTime - lastSendTime < intervalTime) return; // 节流
        boolean isPressed = isPress.getIsKeyPressed();
        if(!isPressed && AllPlayerStatue.getStatue(Minecraft.getMinecraft().thePlayer.getUniqueID()).minerIsOpen) {
            UUID playerUUID = Minecraft.getMinecraft().thePlayer.getUniqueID();
            AllPlayerStatue.getStatue(playerUUID).minerIsOpen = false;
            Qz_MinerSimpleNetwork.sendMessageToServer(new PacketIsHold(false));
        } else if(isPressed && !AllPlayerStatue.getStatue(Minecraft.getMinecraft().thePlayer.getUniqueID()).minerIsOpen) {
            UUID playerUUID = Minecraft.getMinecraft().thePlayer.getUniqueID();
            AllPlayerStatue.getStatue(playerUUID).minerIsOpen = true;
            Qz_MinerSimpleNetwork.sendMessageToServer(new PacketIsHold(true));
        }
    }

    @SubscribeEvent
    public void onRenderGameOverlay(RenderGameOverlayEvent.Post event) {
        if(!(event.type == RenderGameOverlayEvent.ElementType.TEXT)) return;
        mc.mcProfiler.startSection("qz_miner_tip");
        boolean isHold = AllPlayerStatue.getStatue(Minecraft.getMinecraft().thePlayer.getUniqueID()).minerIsOpen;
        FontRenderer fr = mc.fontRenderer;
        // 获取当前界面的宽度和高度
        int screenWidth = mc.displayWidth;
        int screenHeight = mc.displayHeight;
        ScaledResolution resolution = event.resolution;
        int scale = resolution.getScaleFactor();
        String translation1 = I18n.format("key."+ MOD_INFO.MODID +".holdOn.tip");
        String curState = isHold ? I18n.format("key."+ MOD_INFO.MODID +".holdOn.tip.on") : I18n.format("key."+ MOD_INFO.MODID +".holdOn.tip.off");

        int x = (int) (screenWidth * 0.05);
        int y = (int) (screenHeight * 0.95);
        x = x / scale;
        y = y / scale;
        fr.drawString(translation1, x, y, 0xFFFFFF);
        int fontWidth = fr.getStringWidth(translation1);
        if(isHold) {
            fr.drawString(curState, x + fontWidth + 2, y, 0x1eff00);
        } else {
            fr.drawString(curState, x + fontWidth + 2, y, 0xff7500);
        }
        mc.mcProfiler.endSection();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {

    }
}
