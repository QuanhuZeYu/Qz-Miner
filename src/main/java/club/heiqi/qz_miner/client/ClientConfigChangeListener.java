package club.heiqi.qz_miner.client;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigValueBridge;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端配置变更：订阅 UILib {@code BATCH_SAVE} 回灌静态字段并网络同步。
 *
 * <p>在客户端生命周期只订阅一次，避免每次开配置页累积监听器。
 * 已移除 Forge {@code ConfigChangedEvent} 保存链。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientConfigChangeListener implements ConfigChangeListener {

    private static boolean subscribed;

    /**
     * 注册 BATCH_SAVE 单次订阅（幂等）。
     */
    public void register() {
        if (subscribed) {
            return;
        }
        ConfigManager manager = ConfigBootstrap.manager();
        if (manager == null) {
            MyMod.LOG.warn("ConfigManager not ready; BATCH_SAVE listener not subscribed");
            return;
        }
        manager.eventBus().subscribe(this);
        subscribed = true;
        MyMod.LOG.info("Subscribed Config BATCH_SAVE listener once");
    }

    /**
     * 测试钩子：重置单次订阅标记。
     */
    public static void resetSubscriptionForTests() {
        subscribed = false;
    }

    @Override
    public void onConfigChanged(ConfigChangeEvent event) {
        if (event == null || event.getType() != ConfigChangeEvent.ChangeType.BATCH_SAVE) {
            return;
        }
        ConfigManager manager = ConfigBootstrap.manager();
        if (manager == null) {
            return;
        }
        ConfigValueBridge.applyFromAuthority(manager.authority());
        syncClientRequestedChainConfig();
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
