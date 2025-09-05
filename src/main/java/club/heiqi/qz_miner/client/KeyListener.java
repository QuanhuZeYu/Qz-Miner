package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.network.ChainSwitcherPacket;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.settings.KeyBinding;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
public class KeyListener {
    public Logger LOG = LogManager.getLogger();
    // 默认 ~ 键
    public static KeyBinding chainSwitch = new KeyBinding(
            "key.qz_miner.chainSwitch", Keyboard.KEY_GRAVE, "key.categories.qz_miner"
    );
    public boolean onChain = false;

    @SubscribeEvent
    public void onInput(InputEvent event) {
        if (chainSwitch.getIsKeyPressed()) {
            if (!onChain) {
                // 发送网络包
                MyMod.networkMain.network.sendToServer(new ChainSwitcherPacket(true));
                // 修改客户端字段
                ((ClientProxy)MyMod.proxy).minerRenderer.inPressChainKey = true;
            }
            onChain = true;
        }
        if (!chainSwitch.getIsKeyPressed()) {
            if (onChain) {
                // 发送网络包
                MyMod.networkMain.network.sendToServer(new ChainSwitcherPacket(false));
                // 修改客户端字段
                ((ClientProxy)MyMod.proxy).minerRenderer.inPressChainKey = false;
            }
            onChain = false;
        }
    }

    public void registry() {
        FMLCommonHandler.instance().bus().register(this);
    }
}
