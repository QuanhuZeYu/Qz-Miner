package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端配置变更监听。
 */
@SideOnly(Side.CLIENT)
public class ClientConfigChangeListener {

    /**
     * 保存后同步本地状态与服务端请求配置。
     */
    public static void reloadAndSyncAfterConfigSaved() {
        Config.saveAndReload();
        syncClientRequestedChainConfig();
    }

    /**
     * 注册配置变更监听。
     */
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 从配置界面保存后重新加载配置，并同步到服务端。
     *
     * @param event 配置变更事件
     */
    @SubscribeEvent
    public void onConfigChangeEvent(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (!MyMod.MODID.equalsIgnoreCase(event.modID)) {
            return;
        }

        reloadAndSyncAfterConfigSaved();
    }

    /**
     * 将客户端请求的连锁配置同步到本地状态与服务端。
     */
    public static void syncClientRequestedChainConfig() {
        if (MyMod.chainStateService == null) {
            return;
        }

        MyMod.chainStateService.setClientRequestedChainConfig(Config.chainRadius, Config.chainMaxBlocks);
        if (MyMod.networkMain == null || FMLClientHandler.instance().getClient().isSingleplayer()) {
            return;
        }

        MyMod.networkMain.network.sendToServer(new PacketChainConfigRequest(Config.chainRadius, Config.chainMaxBlocks));
    }
}
