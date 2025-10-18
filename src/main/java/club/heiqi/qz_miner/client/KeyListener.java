package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.MinerConfig;
import club.heiqi.qz_miner.core.MinerModeState;
import club.heiqi.qz_miner.network.PacketChainSwitcher;
import club.heiqi.qz_miner.network.PacketMinerConfig;
import club.heiqi.qz_miner.network.PacketMinerModeState;
import club.heiqi.qz_miner.utils.MessageUtils;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@SideOnly(Side.CLIENT)
public class KeyListener {
    public Logger LOG = LogManager.getLogger();
    // 默认 ~ 键
    public static KeyBinding chainSwitch = new KeyBinding(
            "key.qz_miner.chainSwitch", Keyboard.KEY_GRAVE, "key.categories.qz_miner"
    );
    public static KeyBinding mainModeSwitch = new KeyBinding(
            "key.qz_miner.mainModeSwitch", Keyboard.KEY_V/*鼠标中键*/, "key.categories.qz_miner"
    );
    public boolean onChain = false;

    @SubscribeEvent
    public void onInput(InputEvent event) {
        // ========== 切换主模式 ==========
        MinerModeState minerModeState = ((ClientProxy) MyMod.proxy).clientState.minerModeState;
        if (mainModeSwitch.isPressed()) {
            // 打印提示 - 切换到下一个主模式
            MessageUtils.printSelfMessage("当前模式: "+I18n.format(minerModeState.nextMainMode()));
            // 网络同步当前模式
            MyMod.networkMain.network.sendToServer(new PacketMinerModeState(minerModeState));
        }
        // ========== 按下连锁键 ==========
        if (chainSwitch.getIsKeyPressed()) {
            // ===== 状态切换: 开始连锁 =====
            if (!onChain) {
                // ========== 修改连锁状态 ==========
                // 发送网络包
                MyMod.networkMain.network.sendToServer(new PacketChainSwitcher(true));
                // 修改客户端字段
                ((ClientProxy)MyMod.proxy).minerRenderer.inPressChainKey = true;

                // ========== 同步连锁配置 ==========
                MyMod.networkMain.network.sendToServer(new PacketMinerConfig(new MinerConfig()));
            }
            // ===== 持续连锁状态 =====
            onChain = true;
            // ========== 滚轮切换子模式 ==========
            if (event instanceof InputEvent.MouseInputEvent && Mouse.getEventDWheel() != 0) {
                int dWheel = Mouse.getEventDWheel();
                if (dWheel < 0) {
                    minerModeState.nextSecondMode();
                } else if (dWheel > 0) {
                    minerModeState.previousSecondMode();
                }
                // 网络同步当前子模式
                MyMod.networkMain.network.sendToServer(new PacketMinerModeState(minerModeState));
                MessageUtils.printSelfMessage("当前子模式: "+I18n.format(minerModeState.currentSecondMode()));
            }
        }
        // ========== 松开连锁键 ==========
        if (!chainSwitch.getIsKeyPressed()) {
            // ===== 状态切换: 关闭连锁 =====
            if (onChain) {
                // ========== 修改连锁状态 ==========
                // 发送网络包
                MyMod.networkMain.network.sendToServer(new PacketChainSwitcher(false));
                // 修改客户端字段
                ((ClientProxy)MyMod.proxy).minerRenderer.inPressChainKey = false;
            }
            onChain = false;
            // ===== 连锁持续关闭 =====
        }
    }

    public void registry() {
        ClientRegistry.registerKeyBinding(mainModeSwitch);
        ClientRegistry.registerKeyBinding(chainSwitch);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
