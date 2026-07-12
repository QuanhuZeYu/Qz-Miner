package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.chain.ChainConstants;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.network.PacketChainSubModeSwitch;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
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
import org.lwjgl.input.Mouse;

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

    /**
     * 当前是否处于按键按下状态。
     */
    private boolean wasPressed = false;

    /**
     * 注册按键绑定和事件监听。
     */
    public void register() {
        ClientRegistry.registerKeyBinding(chainSwitch);
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 在按住连锁键时，使用滚轮切换主模式或子模式。
     *
     * @param event 鼠标输入事件
     */
    @SubscribeEvent
    public void onMouseInput(InputEvent.MouseInputEvent event) {
        if (FMLClientHandler.instance().getClient().theWorld == null || MyMod.chainStateService == null) {
            return;
        }

        if (!chainSwitch.getIsKeyPressed()) {
            return;
        }

        ChainMode selectedMode = MyMod.chainStateService.getClientState().getSelectedMode();
        ChainSubMode currentSubMode = MyMod.chainStateService.getClientState().getSelectedSubMode();
        if (currentSubMode == null) {
            return;
        }

        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0) {
            return;
        }

        boolean sneakKeyPressed = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT);

        if (sneakKeyPressed) {
            ChainMode nextMode = dWheel < 0
                ? ChainModeRegistry.next(selectedMode)
                : ChainModeRegistry.previous(selectedMode);
            if (nextMode == null || nextMode == selectedMode) {
                return;
            }

            MyMod.chainStateService.setClientSelectedMode(nextMode);
            MyMod.networkMain.network.sendToServer(new PacketChainModeSwitch(nextMode));
            MyMod.LOG.debug("[KeyListener] Switched chain mode to {}", nextMode);
            return;
        }

        ChainSubMode nextSubMode = dWheel < 0
            ? ChainModeRegistry.nextSubMode(selectedMode, currentSubMode)
            : ChainModeRegistry.previousSubMode(selectedMode, currentSubMode);
        if (nextSubMode == null || nextSubMode == currentSubMode) {
            return;
        }

        MyMod.chainStateService.setClientSelectedSubMode(nextSubMode);
        MyMod.networkMain.network.sendToServer(new PacketChainSubModeSwitch(nextSubMode));
        MyMod.LOG.debug("[KeyListener] Switched sub mode to {} under mode {}", nextSubMode, selectedMode);
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
            if (pressed) {
                syncRequestedChainConfigToServer();
            }
            MyMod.networkMain.network.sendToServer(new PacketKeyState(KEY_CHAIN, pressed));
        }
    }

    private void syncRequestedChainConfigToServer() {
        if (MyMod.chainStateService == null || MyMod.networkMain == null) {
            return;
        }

        if (FMLClientHandler.instance().getClient().isSingleplayer()) {
            return;
        }

        ValidatedSnapshot snapshot = ConfigBootstrap.currentValidatedSnapshot();
        MyMod.chainStateService.setClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks);
        MyMod.networkMain.network.sendToServer(new PacketChainConfigRequest(
            MyMod.chainStateService.getClientState().getRequestedChainRadius(),
            MyMod.chainStateService.getClientState().getRequestedChainMaxBlocks()));
    }
}
