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
 *
 * <p>连接 / 断线 / 世界卸载入口先推进 {@link ClientConnectionLifecycle} token，
 * 再调度初始化或清理。单人集成服也推进 token；{@code isSingleplayer} 只影响 C2S request。</p>
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
        // 入口立即推进 active token（单人集成服同样推进），再调度初始化
        ClientConnectionLifecycle.advanceActive();
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
        // 入口立即推进 inactive token，再调度清理
        ClientConnectionLifecycle.advanceInactive();
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

        // 推进 token 保持当前 active 标志：旧世界任务失效，同连接切维度后未来 S2C 仍可捕获新 active token
        ClientConnectionLifecycle.advanceKeepActive();
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
