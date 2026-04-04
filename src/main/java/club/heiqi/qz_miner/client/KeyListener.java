package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.ChainConstants;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.network.PacketKeyState;
import club.heiqi.qz_miner.network.PacketChainModeSwitch;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.settings.KeyBinding;
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
    public static final int KEY_CHAIN = ChainConstants.KEY_CHAIN;

    /**
     * 连锁切换按键绑定，默认 ~ 键。
     */
    public static KeyBinding chainSwitch = new KeyBinding(
            "key.qz_miner.chainSwitch",
            Keyboard.KEY_GRAVE,
            "key.categories.qz_miner");
    public static KeyBinding mainModeSwitch = new KeyBinding(
            "key.qz_miner.mainModeSwitch",
            Keyboard.KEY_V,
            "key.categories.qz_miner");

    private final HudOverlay hudOverlay;

    /**
     * 当前是否处于按键按下状态。
     */
    private boolean wasPressed = false;

    public KeyListener(HudOverlay hudOverlay) {
        this.hudOverlay = hudOverlay;
    }

    /**
     * 注册按键绑定和事件监听。
     */
    public void register() {
        ClientRegistry.registerKeyBinding(chainSwitch);
        ClientRegistry.registerKeyBinding(mainModeSwitch);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (FMLClientHandler.instance().getClient().theWorld == null) {
            return;
        }

        if (mainModeSwitch.isPressed() && MyMod.chainStateService != null) {
            ChainMode nextMode = ChainModeRegistry.next(MyMod.chainStateService.getClientState().getSelectedMode());
            MyMod.chainStateService.setClientSelectedMode(nextMode);
            MyMod.networkMain.network.sendToServer(new PacketChainModeSwitch(nextMode));
            MyMod.LOG.debug("[KeyListener] Switched chain mode to {}", nextMode);
        }

    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (FMLClientHandler.instance().getClient().theWorld == null) {
            if (wasPressed) {
                updateChainKeyState(false);
            }
            return;
        }

        boolean isPressed = chainSwitch.getIsKeyPressed();
        if (isPressed && !wasPressed) {
            updateChainKeyState(true);
        } else if (!isPressed && wasPressed) {
            updateChainKeyState(false);
        }

        wasPressed = isPressed;
    }

    private void updateChainKeyState(boolean pressed) {
        if (pressed) {
            MyMod.LOG.debug("[KeyListener] Chain key pressed");
        } else {
            MyMod.LOG.debug("[KeyListener] Chain key released");
        }

        if (MyMod.chainStateService != null) {
            MyMod.chainStateService.setClientChainKeyPressed(pressed);
        }
        if (MyMod.networkMain != null) {
            MyMod.networkMain.network.sendToServer(new PacketKeyState(KEY_CHAIN, pressed));
        }
        hudOverlay.setChainActive(pressed);
    }
}
