package club.heiqi.qz_miner.client;

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
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * 接收 Miner 已成功提交的不可变令牌，分别派发到 client 与集成服主线程。
 * 远程多人客户端不写 general 字段；连接同步仍使用统一生命周期 token。
 */
@SideOnly(Side.CLIENT)
public class ClientConfigChangeListener implements ConfigBootstrap.CommitListener {

    private static final Object SUBSCRIPTION_LOCK = new Object();

    private static ClientConfigChangeListener subscribedListener;

    private final Mailbox clientMailbox;
    private final Mailbox serverMailbox;

    public ClientConfigChangeListener() {
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
                        if (MyMod.serverConfigHotApplyService != null) {
                            MyMod.serverConfigHotApplyService.apply(committed);
                        } else {
                            ConfigValueBridge.applyGeneralFromSnapshot(committed.snapshot);
                            if (MyMod.autoToolSwapServerBatchService != null) {
                                MyMod.autoToolSwapServerBatchService.publishPolicy(committed);
                            }
                        }
                        MyMod.LOG.debug("Applied general config on server main thread after config change notification");
                    }
                });
    }

    /** 替换时关闭旧 mailbox，使用已提交 current 补种，不额外生成提交令牌。 */
    public void register() {
        CommittedSnapshot seed;
        boolean replacing;
        synchronized (SUBSCRIPTION_LOCK) {
            if (subscribedListener == this) return;
            replacing = subscribedListener != null;
            if (subscribedListener != null) {
                subscribedListener.closeMailboxes();
                ConfigBootstrap.unsubscribe(subscribedListener);
            }
            subscribedListener = this;
            seed = ConfigBootstrap.subscribe(this);
        }
        if (replacing) dispatchCommitted(seed);
    }

    /** 生命周期退订；已排队的旧任务由 mailbox.close 丢弃。 */
    public void unregister() {
        synchronized (SUBSCRIPTION_LOCK) {
            closeMailboxes();
            ConfigBootstrap.unsubscribe(this);
            if (subscribedListener == this) subscribedListener = null;
        }
    }

    @Override
    public void onCommitted(CommittedSnapshot committed) {
        synchronized (SUBSCRIPTION_LOCK) {
            if (subscribedListener != this || !ConfigBootstrap.isCurrent(committed)) return;
        }
        dispatchCommitted(committed);
    }

    private void dispatchCommitted(CommittedSnapshot committed) {
        ConfigSnapshotDispatch.dispatch(
                committed,
                clientMailbox,
                isIntegratedServerRunning(),
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
                    committed.snapshot.autoToolSwapEnabled);
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

    /** 保存/回载 后发送完整对象组配置；revision 与 rules 使用同一不可变提交包装。 */
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

}
