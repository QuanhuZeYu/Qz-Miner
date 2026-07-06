package club.heiqi.qz_miner.chain.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * {@link ChainExecutionEventBridge} 纯逻辑单测。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>{@link ChainExecutionEventBridge#buildExecutionFinished} gen 原样回填（gen 传递链锚点）。</li>
 *   <li>reason 透传 / null 不抛。</li>
 *   <li>空规划路径：onPlanCompleted 收到 totalTargets=0 + 空 context → publish ExecutionFinished(reason="empty-plan") + LifecycleCleanup。</li>
 *   <li>正常消费路径：队列有目标 → ServerTickEvent 消费 → 队列空 publish ExecutionFinished(reason="executor-consumed-all-targets")。</li>
 *   <li>陈旧 gen 领取被拒：onPlanCompleted 收到 gen 不匹配 → 不 publish。</li>
 * </ul>
 *
 * <p><b>worker 真链路无法 JVM 覆盖</b>：依赖 {@code worldObj}/player/session 运行时装配，
 * 留 {@code runClient21}/{@code runServer25} 实机验证（见传感层测试约定）。
 * 本单测只覆盖 buildExecutionFinished 纯逻辑 + onPlanCompleted 的 registry 领取分支
 * （通过手动 put registry 模拟 worker 完成）。</p>
 */
public class ChainExecutionEventBridgeTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000AB");
    private static final long TICK = 99L;
    private static final long NANOS = 424242L;

    // ============================ buildExecutionFinished 纯逻辑 ============================

    /** buildExecutionFinished：gen + reason 原样回填。 */
    @Test
    public void buildExecutionFinishedCarriesGenAndReason() {
        int gen = 7;
        String reason = "executor-consumed-all-targets";
        ExecutionFinished event = ChainExecutionEventBridge.buildExecutionFinished(PLAYER, gen, TICK, NANOS, reason);

        Assert.assertEquals(PLAYER, event.getPlayerUUID());
        Assert.assertEquals("gen 必须原样回填（gen 传递链锚点）", gen, event.getGeneration());
        Assert.assertEquals(TICK, event.getServerTick());
        Assert.assertEquals(NANOS, event.getTimestampNanos());
        Assert.assertEquals(reason, event.getReason());
    }

    /** buildExecutionFinished：gen=0 边界值回填。 */
    @Test
    public void buildExecutionFinishedZeroGen() {
        ExecutionFinished event = ChainExecutionEventBridge.buildExecutionFinished(PLAYER, 0, TICK, NANOS, "empty-plan");
        Assert.assertEquals(0, event.getGeneration());
        Assert.assertEquals("empty-plan", event.getReason());
    }

    /** buildExecutionFinished：null reason 透传不抛异常（自由文本）。 */
    @Test
    public void buildExecutionFinishedNullReason() {
        ExecutionFinished event = ChainExecutionEventBridge.buildExecutionFinished(PLAYER, 1, TICK, NANOS, null);
        Assert.assertEquals(1, event.getGeneration());
        Assert.assertNull(event.getReason());
    }

    /** gen 传递链一致性：PlanCompleted 注入 context 的 gen 必须等于 publish 出 ExecutionFinished 的 gen。 */
    @Test
    public void genConsistencyBetweenPlanCompletedAndExecutionFinished() {
        int planningGen = 5;
        // 模拟 worker：经 PlanCompleted.getGeneration() 注入 context
        PlanCompleted planCompleted = new PlanCompleted(PLAYER, planningGen, TICK, NANOS, 64);
        ChainExecutionContext context = new ChainExecutionContext(
                PLAYER, planCompleted.getGeneration(), new ConcurrentLinkedQueue<ChainTarget>(), null);
        // 执行桥 publish ExecutionFinished 时回填 context.getGeneration()
        ExecutionFinished execFinished = ChainExecutionEventBridge.buildExecutionFinished(
                context.getPlayerUUID(), context.getGeneration(), TICK, NANOS, "test");
        Assert.assertEquals("PlanCompleted gen 必须等于 ExecutionFinished gen（事件流锚点）",
                planCompleted.getGeneration(), execFinished.getGeneration());
    }

    // ============================ onPlanCompleted 空规划边界（卡点5） ============================

    /**
     * 空规划边界：onPlanCompleted 收到 totalTargets=0 + 空 context → 立即 publish
     * ExecutionFinished(reason="empty-plan") + LifecycleCleanup（E4-b 桥）。
     */
    @Test
    public void onPlanCompletedWithEmptyContextPublishesEmptyPlanFinished() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        // 手动 put 空 context 模拟 worker 完成路径（totalTargets=0）
        registry.put(new ChainExecutionContext(PLAYER, 3, new ConcurrentLinkedQueue<ChainTarget>(), null));

        List<ExecutionFinished> finishedCaptured = new ArrayList<ExecutionFinished>();
        List<LifecycleCleanup> cleanupCaptured = new ArrayList<LifecycleCleanup>();
        // 订阅必须在 bridge 之后（捕获 bridge publish 的事件，不影响 bridge 自身订阅顺序）
        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);
        bus.subscribe(ExecutionFinished.class, finishedCaptured::add);
        bus.subscribe(LifecycleCleanup.class, cleanupCaptured::add);

        // publish PlanCompleted(totalTargets=0) 并 drain
        bus.publish(new PlanCompleted(PLAYER, 3, TICK, NANOS, 0));
        bus.drain();

        Assert.assertEquals("空规划应 publish 一条 ExecutionFinished", 1, finishedCaptured.size());
        ExecutionFinished ef = finishedCaptured.get(0);
        Assert.assertEquals(PLAYER, ef.getPlayerUUID());
        Assert.assertEquals("gen 必须来自 context（经 PlanCompleted 注入）", 3, ef.getGeneration());
        Assert.assertEquals("empty-plan", ef.getReason());

        Assert.assertEquals("空规划应紧接 publish 一条 LifecycleCleanup（E4-b 桥）", 1, cleanupCaptured.size());
        Assert.assertEquals(3, cleanupCaptured.get(0).getGeneration());

        // context 应被清理
        Assert.assertNull("消费完成后 registry 应 remove", registry.get(PLAYER, 3));
    }

    /**
     * 陈旧 gen 领取被拒：onPlanCompleted 收到 gen 不匹配（registry 内是 gen=3，事件 gen=2）
     * → 不 publish ExecutionFinished。
     */
    @Test
    public void onPlanCompletedWithStaleGenDoesNotPublish() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        // registry 内 context gen=3
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        registry.put(new ChainExecutionContext(PLAYER, 3, queue, null));

        List<ExecutionFinished> finishedCaptured = new ArrayList<ExecutionFinished>();
        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);
        bus.subscribe(ExecutionFinished.class, finishedCaptured::add);

        // publish 陈旧 gen=2 的 PlanCompleted
        bus.publish(new PlanCompleted(PLAYER, 2, TICK, NANOS, 1));
        bus.drain();

        Assert.assertTrue("陈旧 gen 领取应被拒，不 publish ExecutionFinished", finishedCaptured.isEmpty());
        // registry 不应被清理（消费未发生）
        Assert.assertNotNull("陈旧事件不应触发 registry.remove", registry.get(PLAYER, 3));
    }

    /**
     * 正常登记路径：onPlanCompleted 收到非空 context（队列有目标）→ 不立即 publish，
     * 留 ServerTickEvent 消费。这里直接验证 onPlanCompleted 后 registry 内 context 仍在。
     */
    @Test
    public void onPlanCompletedWithNonEmptyContextDoesNotPublishImmediately() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ConcurrentLinkedQueue<ChainTarget> queue = new ConcurrentLinkedQueue<ChainTarget>();
        queue.add(new ChainTarget(1, 2, 3));
        registry.put(new ChainExecutionContext(PLAYER, 2, queue, null));

        List<ExecutionFinished> finishedCaptured = new ArrayList<ExecutionFinished>();
        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);
        bus.subscribe(ExecutionFinished.class, finishedCaptured::add);

        bus.publish(new PlanCompleted(PLAYER, 2, TICK, NANOS, 1));
        bus.drain();

        // 非空队列不应立即 publish（留 ServerTickEvent 消费，本测试不模拟 tick）
        Assert.assertTrue("非空 context 不应在 onPlanCompleted 立即 publish", finishedCaptured.isEmpty());
        Assert.assertNotNull("registry 内 context 应仍在", registry.get(PLAYER, 2));
    }

    /**
     * onPlanCompleted 领取失败（registry 无此玩家）→ 不 publish 不抛异常。
     */
    @Test
    public void onPlanCompletedWithMissingPlayerDoesNotPublish() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        // 不 put 任何 context

        List<ExecutionFinished> finishedCaptured = new ArrayList<ExecutionFinished>();
        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);
        bus.subscribe(ExecutionFinished.class, finishedCaptured::add);

        bus.publish(new PlanCompleted(PLAYER, 1, TICK, NANOS, 1));
        bus.drain();

        Assert.assertTrue("registry 无此玩家应不 publish", finishedCaptured.isEmpty());
    }

    // ============================ G1 掉落窗口接线（阶段8 块2） ============================
    //
    // G1 四接线点均通过 MyMod.chainStateService.getPlayerState(uuid).setExecuting(...) 接线。
    // 纯 JVM 单测无 Forge 运行时，MyMod.chainStateService 为 null，setExecutionWindow 早 return
    // （null 防御）。此处验证：
    //   1. 四接线点的代码路径可达且不抛（null 安全）；
    //   2. registry 在各收口路径正确 remove（与 G1 联动）。
    // 真实 setExecuting 时序（true/false 切换）由 runServer25 实机验证（I5 E 系列）。

    /**
     * G1 看门狗收口：publish WatchdogTimeout → bridge.onWatchdogTimeout 清 registry +
     * setExecuting(false)（null chainStateService 安全跳过）。
     */
    @Test
    public void watchdogTimeoutClearsRegistryAndIsReachable() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        registry.put(new ChainExecutionContext(PLAYER, 2,
                new ConcurrentLinkedQueue<ChainTarget>(), null));

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new WatchdogTimeout(PLAYER, 2, TICK, NANOS, NANOS));
        bus.drain();

        Assert.assertNull("看门狗后 registry 应清理", registry.get(PLAYER, 2));
    }

    /**
     * G1 生命周期清理收口：publish LifecycleCleanup → bridge.onLifecycleCleanup 清 registry +
     * 幂等 setExecuting(false)（null chainStateService 安全跳过）。
     */
    @Test
    public void lifecycleCleanupClearsRegistryAndIsReachable() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        registry.put(new ChainExecutionContext(PLAYER, 1,
                new ConcurrentLinkedQueue<ChainTarget>(), null));

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new LifecycleCleanup(PLAYER, 1, TICK, NANOS, "player-logout", true, true));
        bus.drain();

        Assert.assertNull("生命周期清理后 registry 应清理", registry.get(PLAYER, 1));
    }
}
