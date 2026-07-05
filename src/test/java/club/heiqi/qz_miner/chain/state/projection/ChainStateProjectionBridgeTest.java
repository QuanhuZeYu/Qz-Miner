package club.heiqi.qz_miner.chain.state.projection;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * {@link ChainStateProjectionBridge} 服务端投影下发桥单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>构造器订阅 ChainPhaseChanged（publish + drain 触发 onPhaseChanged，不 NPE）。</li>
 *   <li>null networkMain → onPhaseChanged 静默跳过（不抛异常）。</li>
 *   <li>null playerManager → onPhaseChanged 静默跳过。</li>
 * </ul>
 *
 * <p><b>真实 sendTo 路径无法 JVM 覆盖</b>：依赖 EntityPlayerMP 实体与 SimpleNetworkWrapper
 * 运行时，留 {@code runClient21} 实机验证（服务端转移 → 客户端投影容器更新）。
 * 本单测只覆盖构造期订阅登记 + 三个 null-path 跳过分支。</p>
 */
public class ChainStateProjectionBridgeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000DD");
    private static final long TICK = 42L;
    private static final long NANOS = 123L;

    /** 构造器订阅 ChainPhaseChanged：publish + drain 触发 onPhaseChanged，因 MyMod 静态 null 跳过，不抛异常。 */
    @Test
    public void constructorSubscribesChainPhaseChanged() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        @SuppressWarnings("unused")
        ChainStateProjectionBridge bridge = new ChainStateProjectionBridge(bus);

        // MyMod.networkMain / playerManager 在纯 JVM 测试环境为 null，onPhaseChanged 应跳过不抛
        bus.publish(new ChainPhaseChanged(PLAYER, 1, ChainPhase.IDLE, ChainPhase.ARMED, TICK, NANOS));
        int processed = bus.drain();
        Assert.assertEquals("drain 应处理 1 个事件", 1, processed);
    }

    /** null networkMain：onPhaseChanged 静默跳过（MyMod.networkMain 默认 null）。 */
    @Test
    public void nullNetworkMainSkipsSilently() {
        Assert.assertNull("测试前提：MyMod.networkMain 应为 null", MyMod.networkMain);
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        @SuppressWarnings("unused")
        ChainStateProjectionBridge bridge = new ChainStateProjectionBridge(bus);

        bus.publish(new ChainPhaseChanged(PLAYER, 2, ChainPhase.ARMED, ChainPhase.PLANNING, TICK, NANOS));
        bus.drain();
        // 不抛异常即通过（null 短路 return）
    }

    /** null playerManager：onPhaseChanged 静默跳过（MyMod.playerManager 默认 null）。 */
    @Test
    public void nullPlayerManagerSkipsSilently() {
        Assert.assertNull("测试前提：MyMod.playerManager 应为 null", MyMod.playerManager);
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        @SuppressWarnings("unused")
        ChainStateProjectionBridge bridge = new ChainStateProjectionBridge(bus);

        bus.publish(new ChainPhaseChanged(PLAYER, 3, ChainPhase.PLANNING, ChainPhase.RUNNING, TICK, NANOS));
        bus.drain();
        // 不抛异常即通过（null 短路 return）
    }
}
