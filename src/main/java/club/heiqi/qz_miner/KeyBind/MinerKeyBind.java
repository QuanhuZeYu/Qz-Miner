package club.heiqi.qz_miner.KeyBind;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.ProxyMinerMode;
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
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;
import net.minecraft.client.settings.KeyBinding;

import java.util.List;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class MinerKeyBind {
    public static final List<String> keyList = ProxyMinerMode.rangeModeListString;
    private static final KeyBinding switchMode = new KeyBinding(
        I18n.format("key."+ MOD_INFO.MODID +".switchMode"), Keyboard.KEY_G, ("key.categories."+ MOD_INFO.MODID)
    );
    private static final KeyBinding isUse = new KeyBinding(
        I18n.format("key."+ MOD_INFO.MODID +".holdOn"), Keyboard.KEY_GRAVE, ("key.categories."+ MOD_INFO.MODID)
    );
    private static final KeyBinding chainModeSwitch = new KeyBinding(
        I18n.format("key."+ MOD_INFO.MODID +".chainModeSwitch"), Keyboard.KEY_C, ("key.categories."+ MOD_INFO.MODID)
    );

    public static void registry() {
        ClientRegistry.registerKeyBinding(switchMode);
        ClientRegistry.registerKeyBinding(isUse);
        ClientRegistry.registerKeyBinding(chainModeSwitch);
        FMLCommonHandler.instance().bus().register(new MinerKeyBind());
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        UUID uuid = Minecraft.getMinecraft().thePlayer.getUniqueID();
        if(uuid == null) {
            MY_LOG.LOG.warn("UUID为空, 忽略按键事件");
            return;
        }
        if(chainModeSwitch.isPressed()) {
            AllPlayerStatue.getStatue(uuid).nextMainMode();
        }
        if(switchMode.isPressed()) {
            AllPlayerStatue.getStatue(uuid).nextMode(event);
        }

        if(isUse.isPressed()) {
            boolean isHold = AllPlayerStatue.getStatue(uuid).minerIsOpen;
            AllPlayerStatue.getStatue(uuid).minerIsOpen = !isHold;
            Qz_MinerSimpleNetwork.sendMessageToServer(new PacketIsHold(!isHold));
            MY_LOG.LOG.info("连锁模式已{}", isHold ? "关闭" : "开启");
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {

    }
}
