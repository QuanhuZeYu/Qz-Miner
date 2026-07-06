package club.heiqi.qz_miner.chain.client.projection;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 阶段6 A2：客户端投影事件订阅者。
 *
 * <p>订阅客户端 {@code clientChainEventBus} 上的 {@link ChainPhaseChanged}（复用 ChainPhaseChanged
 * 类型作投影事件，P1-1=A 决议走 clientChainEventBus），收到后调
 * {@link ClientPhaseProjection#update} 更新容器。</p>
 *
 * <p>事件来源：{@code PacketChainPhaseSnapshot.Handler}（Netty 线程）调
 * {@code ClientProxy.handleClientChainPhaseSnapshot} → 组装 ChainPhaseChanged 投影事件
 * publish 到 clientChainEventBus → {@code ClientChainEventBusDrainer} 在 ClientTickEvent.START
 * drain（客户端主线程）→ 触发本订阅者。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I4</b>：本订阅者仅由客户端主线程 drain 调用（clientChainEventBus.drain 在 ClientTickEvent.START），
 *       实际更新在主线程，不在 Netty 线程直接改容器。</li>
 *   <li><b>I10</b>：clientChainEventBus 是客户端独立实例，物理隔离进不了服务端状态机。</li>
 * </ul>
 */
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
