package club.heiqi.qz_miner.chain.client.projection;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端主线程订阅阶段广播，将服务端快照写入本机投影，供 HUD 与预览读取。 */
@SideOnly(Side.CLIENT)
public class ClientPhaseProjectionSubscriber {

    /** 注入的客户端投影容器。 */
    private final ClientPhaseProjection projection;

    /**
     * 构造订阅者并订阅 clientChainEventBus 上的 ChainPhaseChanged。
     *
     * @param clientChainEventBus 客户端独立事件总线
     * @param projection          客户端投影容器
     */
    public ClientPhaseProjectionSubscriber(ChainEventBus clientChainEventBus, ClientPhaseProjection projection) {
        this.projection = projection;
        clientChainEventBus.subscribe(ChainPhaseChanged.class, this::onPhaseProjected);
    }

    /**
     * 收到投影事件：更新容器（仅客户端主线程 drain 调用）。
     *
     * @param event 进态广播投影事件（携带 to phase + generation + serverTick）
     */
    private void onPhaseProjected(ChainPhaseChanged event) {
        projection.update(event.getToPhase(), event.getGeneration(), event.getServerTick());
    }
}
