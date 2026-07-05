package club.heiqi.qz_miner.chain.state.projection;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.network.PacketChainPhaseSnapshot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 阶段6 A1：连锁状态投影下发桥（服务端订阅者）。
 *
 * <p>订阅 {@link ChainPhaseChanged}（状态机 applyTransition 进态广播）→ 解析玩家 → 组装
 * {@link PacketChainPhaseSnapshot} 下发到客户端投影容器。客户端投影<b>只可见不夺权</b>
 * （P0-1=A 决议），HUD/预览锁定权威仍读旧 {@code serverExecutionStatus}，阶段8 才切换。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本桥只 {@code sendTo} 下发快照，<b>绝不</b>调 {@code applyTransition}/
 *       {@code transition}。状态机仍是 phase/generation 唯一写权威。</li>
 *   <li><b>I4</b>：本桥仅在主线程 drain 调用（ChainPhaseChanged 由主线程 drain publish 触发订阅者），
 *       {@code sendTo} 是网络层入队操作，本身线程安全。</li>
 *   <li><b>I10</b>：本桥是只读订阅者，不改状态机态。</li>
 * </ul>
 *
 * <h3>影子并行边界</h3>
 * <p>本桥与旧 {@code ChainStateService.syncPlayerState} 八字段同步并存，旧链路 HUD/预览锁定权威
 * 不受影响（守 G2 铁律）。新快照包与旧 {@code PacketChainStateSync} 并行下发，阶段8 才切换权威源。</p>
 */
public class ChainStateProjectionBridge {

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;

    /**
     * 构造桥并订阅 {@link ChainPhaseChanged}（构造期订阅生效，无需单独 bootstrap）。
     *
     * @param bus 事件总线
     */
    public ChainStateProjectionBridge(ChainEventBus bus) {
        this.bus = bus;
        bus.subscribe(ChainPhaseChanged.class, this::onPhaseChanged);
    }

    /**
     * 收到进态广播：解析玩家 → 组装快照包 → sendTo 客户端。
     *
     * <p>契约：仅主线程 drain 调用。玩家解析模式对齐 {@code ChainStateService.syncPlayerState}
     * （用 {@code MyMod.playerManager.getPlayer}，非 {@code EntityPlayerMP} 跳过——
     * 单人内部服务器仍是 MP，跳过纯防御）。</p>
     *
     * <p>守 I1：只 sendTo，绝不调 applyTransition/transition（加注释明示）。</p>
     *
     * @param event 进态广播事件（携带 from/to + generation + serverTick）
     */
    private void onPhaseChanged(ChainPhaseChanged event) {
        UUID playerUUID = event.getPlayerUUID();
        // 守 I1：只读订阅，不调 applyTransition；下面只 sendTo 下发快照
        if (MyMod.networkMain == null || MyMod.playerManager == null) {
            return;
        }
        EntityPlayer player = MyMod.playerManager.getPlayer(playerUUID);
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        PacketChainPhaseSnapshot packet = new PacketChainPhaseSnapshot(
                event.getToPhaseOrdinal(),
                event.getGeneration(),
                event.getServerTick());
        MyMod.networkMain.network.sendTo(packet, (EntityPlayerMP) player);
    }
}
