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
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientAdapter;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

/**
 * 客户端按键监听器。
 *
 * 监听 ` 键的长按和松开事件，并通过网络包同步到服务端。
 */
@SideOnly(Side.CLIENT)
public class KeyListener {

    private final AutoToolSwapClientAdapter autoToolSwapAdapter;

    /** 创建按键监听器。 */
    public KeyListener() {
        this(null);
    }

    /**
     * 创建并注入自动工具 adapter。
     *
     * @param autoToolSwapAdapter 客户端唯一 adapter；null 保留兼容测试路径
     */
    public KeyListener(AutoToolSwapClientAdapter autoToolSwapAdapter) {
        this.autoToolSwapAdapter = autoToolSwapAdapter;
    }

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
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 在按住连锁键时，使用滚轮切换主模式或子模式。
     *
     * @param event Forge 可取消鼠标事件
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouseWheel(MouseEvent event) {
        net.minecraft.client.Minecraft client = FMLClientHandler.instance().getClient();
        if (!WheelChordPolicy.shouldConsume(event.dwheel,
                chainSwitch.getIsKeyPressed(),
                client.currentScreen == null,
                client.theWorld != null,
                client.thePlayer != null,
                MyMod.chainStateService != null)) {
            return;
        }
        event.setCanceled(true);

        ChainMode selectedMode = MyMod.chainStateService.getClientState().getSelectedMode();
        ChainSubMode currentSubMode = MyMod.chainStateService.getClientState().getSelectedSubMode();
        if (currentSubMode == null) {
            return;
        }

        int dWheel = event.dwheel;
        boolean sneakKeyPressed = client.gameSettings.keyBindSneak.getIsKeyPressed();

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
            wasPressed = false;
            if (autoToolSwapAdapter != null) {
                autoToolSwapAdapter.onClientTick();
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
        if (autoToolSwapAdapter != null) {
            if (autoToolSwapAdapter.onClientTick()) {
                sendFreshChainKeyPressedToServer();
            }
        }
    }

    private void updateChainKeyState(boolean pressed) {
        if (autoToolSwapAdapter != null) {
            autoToolSwapAdapter.onChainKeyState(pressed);
        }
        if (pressed) {
            MyMod.LOG.debug("[KeyListener] Chain key pressed");
        } else {
            MyMod.LOG.debug("[KeyListener] Chain key released");
        }

        if (pressed) {
            sendFreshChainKeyPressedToServer();
            return;
        }
        if (MyMod.chainStateService != null) {
            MyMod.chainStateService.setClientChainKeyPressed(false);
        }
        if (MyMod.networkMain != null) {
            MyMod.networkMain.network.sendToServer(new PacketKeyState(KEY_CHAIN, false));
        }
    }

    /** 复用真实上升沿的配置同步与 key=true 发包，但不制造新的物理边沿。 */
    private void sendFreshChainKeyPressedToServer() {
        if (MyMod.chainStateService != null) {
            MyMod.chainStateService.setClientChainKeyPressed(true);
        }
        if (MyMod.networkMain != null) {
            syncRequestedChainConfigToServer();
            MyMod.networkMain.network.sendToServer(new PacketKeyState(KEY_CHAIN, true));
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
            MyMod.chainStateService.getClientState().getRequestedChainMaxBlocks(),
            snapshot.tunnelDirectionSource));
    }
}
