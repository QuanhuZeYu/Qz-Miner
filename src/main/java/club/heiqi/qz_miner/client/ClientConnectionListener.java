package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

/**
 * 客户端连接与世界生命周期监听。
 *
 * <p>connect/disconnect 以 {@code event.handler}（{@code INetHandler}）对象 identity
 * 绑定 {@link ClientConnectionLifecycle}；world load/unload 绑定远端 world 对象。
 * 初始化与清理均携带转移 token，主线程 gate 后执行；旧连接/旧世界迟到事件 no-op。</p>
 */
@SideOnly(Side.CLIENT)
public class ClientConnectionListener {

    /**
     * 注册客户端连接与世界生命周期监听。
     */
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
    }

    /**
     * 连上服务器：以 event.handler 建 connection token，主线程仅在该连接仍 current+active 时
     * reset 连接投影并（非单人）发 C2S。A init 排队后若已切到 B，A init no-op。
     *
     * @param event 客户端连服事件
     */
    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        final ClientConnectionLifecycle.Token token = ClientConnectionLifecycle.connect(event.handler);
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfConnectionCurrentAndActive(token, new Runnable() {
                    @Override
                    public void run() {
                        initializeConnectionState();
                    }
                });
            }
        });
    }

    /**
     * 断线：仅 handler 为当前连接且 active 时转 inactive 并排队 cleanup。
     * 迟到/重复 disconnect no-op，不调度新清理。
     *
     * @param event 客户端断线事件
     */
    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientConnectionLifecycle.DisconnectResult result =
                ClientConnectionLifecycle.disconnect(event.handler);
        if (!result.transitioned()) {
            return;
        }
        final ClientConnectionLifecycle.Token cleanupToken = result.token();
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfInactiveDisconnectCurrent(cleanupToken, new Runnable() {
                    @Override
                    public void run() {
                        cleanupLifecycleResources("client-disconnect");
                    }
                });
            }
        });
    }

    /**
     * 客户端远端世界加载：绑定 world identity；首次绑定不废弃连接级 config token。
     *
     * @param event 世界加载事件
     */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (event.world == null || !event.world.isRemote) {
            return;
        }
        ClientConnectionLifecycle.bindWorld(event.world);
    }

    /**
     * 客户端世界卸载：仅当前 world identity 且连接仍 current 时解绑并清理。
     * 旧 world / 重复 unload / disconnect 后 unload no-op。
     *
     * @param event 世界卸载事件
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == null || !event.world.isRemote) {
            return;
        }
        ClientConnectionLifecycle.WorldUnbindResult result =
                ClientConnectionLifecycle.unbindWorld(event.world);
        if (!result.transitioned()) {
            return;
        }
        final ClientConnectionLifecycle.Token cleanupToken = result.token();
        ClientMainThreadDispatcher.run(new Runnable() {
            @Override
            public void run() {
                ClientConnectionLifecycle.runIfWorldUnbindCurrent(cleanupToken, new Runnable() {
                    @Override
                    public void run() {
                        cleanupLifecycleResources("client-world-unload");
                    }
                });
            }
        });
    }

    /**
     * 连接初始化：requested 取本地 validated snapshot；server 投影回落 snapshot 且 matchedCount=0，
     * 防止新服务器首包前沿用旧值。非单人发 C2S。
     *
     * <p>须在 connection-active gate 内调用。</p>
     */
    private void initializeConnectionState() {
        if (MyMod.chainStateService == null) {
            return;
        }
        final ValidatedSnapshot snapshot = ConfigBootstrap.currentValidatedSnapshot();
        MyMod.chainStateService.setClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks);
        // 只改必要客户端投影：server radius/maxBlocks 回落本地 snapshot，matchedCount 清零
        MyMod.chainStateService.getClientState().setServerChainRadius(snapshot.chainRadius);
        MyMod.chainStateService.getClientState().setServerChainMaxBlocks(snapshot.chainMaxBlocks);
        MyMod.chainStateService.getClientState().setServerMatchedTargetCount(0);

        if (MyMod.networkMain == null || FMLClientHandler.instance().getClient().isSingleplayer()) {
            return;
        }
        MyMod.networkMain.network.sendToServer(
                new PacketChainConfigRequest(snapshot.chainRadius, snapshot.chainMaxBlocks));
    }

    /**
     * 统一清理：停预览、释放 GPU、清 phase、清 client event bus pending。
     *
     * <p>须在对应 lifecycle gate 内调用；禁反向调用 lifecycle 入口。</p>
     *
     * @param reason 清理原因
     */
    private void cleanupLifecycleResources(String reason) {
        MyMod.LOG.debug("[ChainPreview] Cleaning preview lifecycle resources, reason={}", reason);
        if (ClientProxy.chainPreviewController != null) {
            ClientProxy.chainPreviewController.stopPreviewForLifecycle();
        }
        if (ClientProxy.chainPreviewRenderer != null) {
            ClientProxy.chainPreviewRenderer.disposeForLifecycle();
        }
        if (ClientProxy.clientPhaseProjection != null) {
            ClientProxy.clientPhaseProjection.clear();
        }
        // 防旧 phase 在 cleanup 后仍 drain 回写投影
        if (MyMod.clientChainEventBus != null) {
            MyMod.clientChainEventBus.clearPending();
        }
    }
}
