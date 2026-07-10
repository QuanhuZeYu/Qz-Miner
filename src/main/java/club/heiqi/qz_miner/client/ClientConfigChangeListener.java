package club.heiqi.qz_miner.client;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch;
import club.heiqi.qz_miner.config.ConfigValueBridge;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * 客户端配置变更：订阅 UILib {@code BATCH_SAVE}，分侧发布运行字段并网络同步。
 *
 * <p>持有注册时的 final {@link ConfigManager}，静态记录准确的 manager+listener 订阅对。
 * BATCH_SAVE 可能同步触发：在回调线程立即抓取不可变 {@link ValidatedSnapshot}，再经
 * {@link ClientMainThreadDispatcher} 异步发布 client 字段；general 仅在集成服运行时经
 * {@link ServerMainThreadDispatcher} 写服务端主线程。远程多人客户端不写 general static 充当服务端权威。</p>
 *
 * <p>UILib 4.5.2 在写盘前执行 Qz-Miner DraftValidator；本回调只处理成功提交。
 * 回调同步捕获并发布 currentValidatedSnapshot，不做事后恢复或二次写盘。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientConfigChangeListener implements ConfigChangeListener {

    private static final Object SUBSCRIPTION_LOCK = new Object();

    private static ConfigManager subscribedManager;
    private static ClientConfigChangeListener subscribedListener;

    private final ConfigManager manager;

    /**
     * @param manager 注册时的 ConfigManager（final 持有）
     */
    public ClientConfigChangeListener(ConfigManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("manager must not be null");
        }
        this.manager = manager;
    }

    /**
     * 无参构造：使用 {@link ConfigBootstrap#manager()}（生产路径）。
     */
    public ClientConfigChangeListener() {
        this(requireBootstrapManager());
    }

    private static ConfigManager requireBootstrapManager() {
        ConfigManager m = ConfigBootstrap.manager();
        if (m == null) {
            throw new IllegalStateException("ConfigBootstrap.manager() is null; preInit must run first");
        }
        return m;
    }

    /**
     * 按 manager 实例幂等订阅。
     */
    public void register() {
        synchronized (SUBSCRIPTION_LOCK) {
            if (subscribedManager == manager && subscribedListener == this) {
                return;
            }
            if (subscribedManager != null && subscribedListener != null) {
                MyMod.LOG.warn("Replacing BATCH_SAVE subscription for ConfigManager/listener instance");
                subscribedManager.eventBus().unsubscribe(subscribedListener);
                subscribedManager = null;
                subscribedListener = null;
            }
            manager.eventBus().subscribe(this);
            subscribedManager = manager;
            subscribedListener = this;
            MyMod.LOG.info("Subscribed Config BATCH_SAVE listener for manager/listener instance");
        }
    }

    /**
     * 测试钩子。
     */
    public static void resetSubscriptionForTests() {
        synchronized (SUBSCRIPTION_LOCK) {
            if (subscribedManager != null && subscribedListener != null) {
                subscribedManager.eventBus().unsubscribe(subscribedListener);
            }
            subscribedManager = null;
            subscribedListener = null;
        }
    }

    @Override
    public void onConfigChanged(ConfigChangeEvent event) {
        if (event == null || event.getType() != ConfigChangeEvent.ChangeType.BATCH_SAVE) {
            return;
        }
        final ValidatedSnapshot snapshot = ConfigBootstrap.captureCommittedSnapshot(manager);
        ConfigSnapshotDispatch.dispatch(
                snapshot,
                new ConfigSnapshotDispatch.Dispatcher() {
                    @Override
                    public void dispatch(Runnable task) {
                        ClientMainThreadDispatcher.run(task);
                    }
                },
                new ConfigSnapshotDispatch.Publication() {
                    @Override
                    public void publish(ValidatedSnapshot committed) {
                        publishClientAndRequest(committed);
                    }
                },
                isIntegratedServerRunning(),
                new ConfigSnapshotDispatch.Dispatcher() {
                    @Override
                    public void dispatch(Runnable task) {
                        ServerMainThreadDispatcher.run(task);
                    }
                },
                new ConfigSnapshotDispatch.Publication() {
                    @Override
                    public void publish(ValidatedSnapshot committed) {
                        ConfigValueBridge.applyGeneralFromSnapshot(committed);
                        MyMod.LOG.debug("Applied general config on server main thread after BATCH_SAVE");
                    }
                });
    }

    private void publishClientAndRequest(ValidatedSnapshot snapshot) {
        ConfigValueBridge.applyClientFromSnapshot(snapshot);
        syncClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks);
    }

    /**
     * 将客户端请求的连锁配置同步到本地状态与服务端。
     *
     * @param requestedRadius    已校验半径
     * @param requestedMaxBlocks 已校验上限
     */
    public static void syncClientRequestedChainConfig(int requestedRadius, int requestedMaxBlocks) {
        if (MyMod.chainStateService == null) {
            return;
        }

        MyMod.chainStateService.setClientRequestedChainConfig(requestedRadius, requestedMaxBlocks);
        if (MyMod.networkMain == null || FMLClientHandler.instance().getClient().isSingleplayer()) {
            return;
        }

        MyMod.networkMain.network.sendToServer(new PacketChainConfigRequest(requestedRadius, requestedMaxBlocks));
    }

    /**
     * 从当前已验证快照同步请求值（登录等路径）。
     */
    public static void syncClientRequestedChainConfig() {
        ValidatedSnapshot snapshot = ConfigBootstrap.currentValidatedSnapshot();
        syncClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks);
    }

    private static boolean isIntegratedServerRunning() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || !mc.isSingleplayer()) {
                return false;
            }
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            return server != null;
        } catch (RuntimeException e) {
            return false;
        } catch (LinkageError e) {
            return false;
        }
    }
}
