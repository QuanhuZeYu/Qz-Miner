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
 * 客户端连接事件监听。
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

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        ClientMainThreadDispatcher.run(() -> {
            final ValidatedSnapshot snapshot = ConfigBootstrap.currentValidatedSnapshot();
            if (MyMod.chainStateService == null) {
                return;
            }

            MyMod.chainStateService.setClientRequestedChainConfig(snapshot.chainRadius, snapshot.chainMaxBlocks);
            if (MyMod.networkMain == null || FMLClientHandler.instance().getClient().isSingleplayer()) {
                return;
            }

            MyMod.networkMain.network.sendToServer(
                    new PacketChainConfigRequest(snapshot.chainRadius, snapshot.chainMaxBlocks));
        });
    }

    /**
     * 在客户端断开连接时清理预览任务与 GPU 缓存。
     *
     * @param event 客户端断线事件
     */
    @SubscribeEvent
    public void onClientDisconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        cleanupPreviewResources("client-disconnect");
    }

    /**
     * 在客户端世界卸载时清理预览任务与 GPU 缓存。
     *
     * @param event 世界卸载事件
     */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.world == null || !event.world.isRemote) {
            return;
        }

        cleanupPreviewResources("client-world-unload");
    }

    /**
     * 统一清理客户端预览运行态与 GPU 资源。
     *
     * @param reason 清理原因
     */
    private void cleanupPreviewResources(String reason) {
        ClientMainThreadDispatcher.run(() -> cleanupPreviewResourcesOnClientThread(reason));
    }

    /**
     * 在客户端主线程执行预览资源清理。
     *
     * @param reason 清理原因
     */
    private void cleanupPreviewResourcesOnClientThread(String reason) {
        MyMod.LOG.debug("[ChainPreview] Cleaning preview lifecycle resources, reason={}", reason);
        if (ClientProxy.chainPreviewController != null) {
            ClientProxy.chainPreviewController.stopPreviewForLifecycle();
        }
        if (ClientProxy.chainPreviewRenderer != null) {
            ClientProxy.chainPreviewRenderer.disposeForLifecycle();
        }
        // 阶段6：玩家断线/切维度/世界卸载时清客户端投影容器（守 I7：客户端生命周期清理）
        if (ClientProxy.clientPhaseProjection != null) {
            ClientProxy.clientPhaseProjection.clear();
        }
    }
}
