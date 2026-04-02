package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.network.PacketKeyState;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;

/**
 * 客户端按键监听器。
 *
 * 监听 ` 键的长按和松开事件，并通过网络包同步到服务端。
 */
@SideOnly(Side.CLIENT)
public class KeyListener {

    /**
     * 按键标识符常量。
     */
    public static final int KEY_CHAIN = 0;

    /**
     * 连锁切换按键绑定，默认 ~ 键。
     */
    public static KeyBinding chainSwitch = new KeyBinding(
            "key.qz_miner.chainSwitch",
            Keyboard.KEY_GRAVE,
            "key.categories.qz_miner");

    /**
     * 当前是否处于按键按下状态。
     */
    private boolean wasPressed = false;

    /**
     * 注册按键绑定和事件监听。
     */
    public void register() {
        ClientRegistry.registerKeyBinding(chainSwitch);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onInput(InputEvent.KeyInputEvent event) {
        boolean isPressed = chainSwitch.getIsKeyPressed();

        if (isPressed && !wasPressed) {
            MyMod.LOG.debug("[KeyListener] Chain key pressed");
            MyMod.networkMain.network.sendToServer(new PacketKeyState(KEY_CHAIN, true));
        } else if (!isPressed && wasPressed) {
            MyMod.LOG.debug("[KeyListener] Chain key released");
            MyMod.networkMain.network.sendToServer(new PacketKeyState(KEY_CHAIN, false));
        }

        wasPressed = isPressed;
    }
}