package club.heiqi.qz_miner.client;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.config.CommittedSnapshot;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch;
import club.heiqi.qz_miner.config.ConfigSnapshotDispatch.Mailbox;
import club.heiqi.qz_miner.config.ConfigValueBridge;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import club.heiqi.qz_miner.network.PacketObjectGroupConfigRequest;
import club.heiqi.qz_miner.network.ObjectGroupWireConfig;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * 客户端配置变更：订阅 UILib {@code BATCH_SAVE}/{@code RELOAD}，分侧发布运行字段并网络同步。
 *
 * <p>持有注册时的 final {@link ConfigManager}，静态记录准确的 manager+listener 订阅对。
 * BATCH_SAVE/RELOAD 可能同步触发：在回调线程立即抓取不可变 {@link ValidatedSnapshot}，再经
 * {@link ClientMainThreadDispatcher} 异步发布 client 字段；general 仅在集成服运行时经
 * {@link ServerMainThreadDispatcher} 写服务端主线程。远程多人客户端不写 general static 充当服务端权威。</p>
 *
 * <p>UILib 4.5.3-beta-7 在写盘前执行 Qz-Miner DraftValidator；本回调只处理成功提交或成功回载。
 * 回调同步捕获并发布完整 {@link CommittedSnapshot}，revision 与对象组规则不再分开读取。</p>
 *
 * <p>listener 替换完成后在 {@code SUBSCRIPTION_LOCK} 内用
 * {@link ConfigBootstrap#captureCommittedSnapshot(ConfigManager)} 重新捕获 Authority
 * （覆盖“已保存但 current 尚未 capture”的 COW 交接窗口），再锁外 dispatch。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientConfigChangeListener implements ConfigChangeListener {

    private static final Object SUBSCRIPTION_LOCK = new Object();

    private static ConfigManager subscribedManager;
    private static ClientConfigChangeListener subscribedListener;

    private final ConfigManager manager;
    private final Mailbox clientMailbox;
    private final Mailbox serverMailbox;
    private final IntegratedServerProbe integratedServerProbe;

    /**
     * @param manager 注册时的 ConfigManager（final 持有）
     */
    public ClientConfigChangeListener(ConfigManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("manager must not be null");
        }
        this.manager = manager;
        this.integratedServerProbe = new IntegratedServerProbe() {
            @Override
            public boolean isRunning() {
                return isIntegratedServerRunning();
            }
        };
        this.clientMailbox = new Mailbox(
                "client",
                new ConfigSnapshotDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ClientMainThreadDispatcher.tryRun(task);
                    }
                },
                new ConfigSnapshotDispatch.CommittedPublication() {
                    @Override
                    public void publish(CommittedSnapshot committed) {
                        publishClientAndRequest(committed);
                    }
                });
        this.serverMailbox = new Mailbox(
                "server",
                new ConfigSnapshotDispatch.Dispatcher() {
                    @Override
                    public boolean dispatch(Runnable task) {
                        return ServerMainThreadDispatcher.tryRun(task);
                    }
                },
                new ConfigSnapshotDispatch.CommittedPublication() {
                    @Override
                    public void publish(CommittedSnapshot committed) {
                        ConfigValueBridge.applyGeneralFromSnapshot(committed.snapshot);
                        MyMod.LOG.debug("Applied general config on server main thread after config change notification");
                    }
                });
    }

    ClientConfigChangeListener(
            ConfigManager manager,
            Mailbox clientMailbox,
            Mailbox serverMailbox,
            IntegratedServerProbe integratedServerProbe) {
        if (manager == null || clientMailbox == null || serverMailbox == null || integratedServerProbe == null) {
            throw new IllegalArgumentException("manager/mailboxes/integrated probe must not be null");
        }
        this.manager = manager;
        this.clientMailbox = clientMailbox;
        this.serverMailbox = serverMailbox;
        this.integratedServerProbe = integratedServerProbe;
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
     *
     * <p>替换路径：unsubscribe/subscribe/active 切换完成后，在锁内
     * {@link ConfigBootstrap#captureCommittedSnapshot} 重新捕获 Authority，再锁外 dispatch。
     * 不仅 seed current，以覆盖 COW 事件快照下“已保存但 current 尚未 capture”的窗口。</p>
     */
    public void register() {
        CommittedSnapshot seed = null;
        synchronized (SUBSCRIPTION_LOCK) {
            if (subscribedManager == manager && subscribedListener == this) {
                return;
            }
            boolean replacing = subscribedManager != null && subscribedListener != null;
            if (subscribedManager != null && subscribedListener != null) {
                MyMod.LOG.warn("Replacing config change subscription for ConfigManager/listener instance");
                subscribedListener.closeMailboxes();
                subscribedManager.eventBus().unsubscribe(subscribedListener);
                subscribedManager = null;
                subscribedListener = null;
            }
            manager.eventBus().subscribe(this);
            subscribedManager = manager;
            subscribedListener = this;
            MyMod.LOG.info("Subscribed Config BATCH_SAVE/RELOAD listener for manager/listener instance");
            if (replacing && manager == ConfigBootstrap.manager()) {
                // 重新捕获 Authority，而非仅 seed 可能尚未更新的 current
                seed = ConfigBootstrap.captureCommittedSnapshot(manager);
            }
        }
        if (seed != null) {
            dispatchCommitted(seed);
        }
    }

    /**
     * 测试钩子。
     */
    public static void resetSubscriptionForTests() {
        synchronized (SUBSCRIPTION_LOCK) {
            if (subscribedManager != null && subscribedListener != null) {
                subscribedListener.closeMailboxes();
                subscribedManager.eventBus().unsubscribe(subscribedListener);
            }
            subscribedManager = null;
            subscribedListener = null;
        }
    }

    @Override
    public void onConfigChanged(ConfigChangeEvent event) {
        if (event == null || !isSupportedChangeType(event.getType())) {
            return;
        }
        MyMod.LOG.debug("Captured config change notification type={}", event.getType());
        CommittedSnapshot committed;
        synchronized (SUBSCRIPTION_LOCK) {
            if (subscribedManager != manager
                    || subscribedListener != this
                    || manager != ConfigBootstrap.manager()) {
                return;
            }
            committed = ConfigBootstrap.captureCommittedSnapshot(manager);
        }
        dispatchCommitted(committed);
    }

    /**
     * 只接受会改变已提交 Authority 的通知；字段级草稿事件不触发运行态回灌。
     *
     * @param type UILib 配置事件类型
     * @return BATCH_SAVE 或 RELOAD 时为 true
     */
    private static boolean isSupportedChangeType(ConfigChangeEvent.ChangeType type) {
        return type == ConfigChangeEvent.ChangeType.BATCH_SAVE
                || type == ConfigChangeEvent.ChangeType.RELOAD;
    }

    private void dispatchCommitted(CommittedSnapshot committed) {
        ConfigSnapshotDispatch.dispatch(
                committed,
                clientMailbox,
                integratedServerProbe.isRunning(),
                serverMailbox);
    }

    private void closeMailboxes() {
        clientMailbox.close();
        serverMailbox.close();
    }

    private void publishClientAndRequest(CommittedSnapshot committed) {
        ConfigValueBridge.applyClientFromSnapshot(committed.snapshot);
        if (ClientProxy.autoToolSwapAdapter != null) {
            ClientProxy.autoToolSwapAdapter.onConfigChanged(
                    committed.snapshot.autoToolSwapEnabled,
                    committed.snapshot.autoToolTakeoverEnabled,
                    committed.snapshot.autoToolPrioritySelectors);
        }
        syncClientRequestedChainConfig(committed.snapshot.chainRadius, committed.snapshot.chainMaxBlocks,
                committed.snapshot.tunnelDirectionSource);
        syncClientObjectGroups(committed);
    }

    /**
     * 将客户端请求的连锁配置同步到本地状态与服务端。
     *
     * @param requestedRadius    已校验半径
     * @param requestedMaxBlocks 已校验上限
     */
    public static void syncClientRequestedChainConfig(int requestedRadius, int requestedMaxBlocks) {
        syncClientRequestedChainConfig(requestedRadius, requestedMaxBlocks,
                TunnelDirectionSource.legacyDefault());
    }

    /** 将客户端 requested 三字段发送给服务端终裁。 */
    public static void syncClientRequestedChainConfig(int requestedRadius, int requestedMaxBlocks,
            TunnelDirectionSource source) {
        if (MyMod.chainStateService == null) {
            return;
        }

        MyMod.chainStateService.setClientRequestedChainConfig(requestedRadius, requestedMaxBlocks);
        if (MyMod.networkMain == null) {
            return;
        }

        MyMod.networkMain.network.sendToServer(new PacketChainConfigRequest(
                requestedRadius, requestedMaxBlocks, source));
    }

    /**
     * 从当前已验证快照同步请求值（登录等路径）。
     */
    public static void syncClientRequestedChainConfig() {
        ValidatedSnapshot snapshot = ConfigBootstrap.currentValidatedSnapshot();
        syncClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks,
                snapshot.tunnelDirectionSource);
    }

    /** 保存/RELOAD 后发送完整对象组配置；revision 与 rules 使用同一不可变提交包装。 */
    public static void syncClientObjectGroups(CommittedSnapshot committed) {
        if (committed == null || MyMod.networkMain == null) {
            return;
        }
        ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.capture();
        if (!token.isConnectionActive() || MyMod.chainStateService == null) {
            return;
        }
        MyMod.chainStateService.getClientState().registerObjectGroupRequest(
                token.connectionGeneration(), committed);
        MyMod.networkMain.network.sendToServer(new PacketObjectGroupConfigRequest(
                ObjectGroupWireConfig.fromRuleSet(committed.epoch, committed.snapshot.objectGroups)));
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

    /** 集成服运行态探针，测试中避免加载 Minecraft 页面或 GL。 */
    interface IntegratedServerProbe {
        /** @return 当前是否需要发布服务端 general */
        boolean isRunning();
    }
}
