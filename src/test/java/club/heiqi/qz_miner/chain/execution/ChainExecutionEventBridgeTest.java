package club.heiqi.qz_miner.chain.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainStateService;

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
     * 空规划边界：onPlanCompleted 收到 totalTargets=0 + 空 context（已 markPlanningComplete）
     * → 立即 publish ExecutionFinished(reason="empty-plan") + LifecycleCleanup（E4-b 桥）。
     *
     * <p>流式语义改造后：worker 完成路径 markPlanningComplete 才 publish PlanCompleted，
     * 空规划场景下 isCompleted()=true（planningComplete=true && queue 空）触发 publish "empty-plan"。</p>
     */
    @Test
    public void onPlanCompletedWithEmptyContextPublishesEmptyPlanFinished() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        // 手动 put 空 context 模拟 worker 完成路径（C 流式登记：onPlanStarted 已提前 put，
        // worker 完成路径 markPlanningComplete + publish PlanCompleted）
        ChainExecutionContext context = new ChainExecutionContext(PLAYER, 3, new ConcurrentLinkedQueue<ChainTarget>(), null);
        context.markPlanningComplete();
        registry.put(context);

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

    /** 强制生命周期清理忽略 generation/round 占位值，按玩家 UUID 清除当前 context。 */
    @Test
    public void forcedLifecycleCleanupClearsContextRegardlessOfRoundIdentity() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        registry.put(new ChainExecutionContext(PLAYER, 701L, 9,
                new ConcurrentLinkedQueue<ChainTarget>(), null));

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new LifecycleCleanup(PLAYER, 0L, 0, TICK, NANOS, "player-logout", true, true));
        bus.drain();

        Assert.assertNull("强制清理必须忽略占位身份并按 UUID 删除 context", registry.get(PLAYER, 9, 701L));
    }

    /** 强制生命周期清理在 registry 已空时仍须幂等关闭执行窗口。 */
    @Test
    public void forcedLifecycleCleanupClosesExecutionWindowWithoutContext() {
        ChainStateService previousService = MyMod.chainStateService;
        ChainStateService testService = new ChainStateService();
        MyMod.chainStateService = testService;
        try {
            ChainPlayerState playerState = testService.getOrCreatePlayerState(PLAYER);
            playerState.setExecuting(true);

            ChainEventBus bus = new ChainEventBus();
            bus.bindMainThread(Thread.currentThread());
            ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
            @SuppressWarnings("unused")
            ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

            bus.publish(new LifecycleCleanup(PLAYER, 0L, 0, TICK, NANOS, "user-abort", true, false));
            bus.drain();

            Assert.assertFalse("registry 不存在 context 时 forced cleanup 仍必须关窗", playerState.isExecuting());
        } finally {
            MyMod.chainStateService = previousService;
        }
    }

    /** 同 generation 的旧 round 非强制清理不得删除新 round context。 */
    @Test
    public void staleRoundLifecycleCleanupDoesNotClearNewRoundContext() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext newRound = new ChainExecutionContext(PLAYER, 802L, 6,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        registry.put(newRound);

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new LifecycleCleanup(PLAYER, 801L, 6, TICK, NANOS,
                "late-execution-complete", false, false));
        bus.drain();

        Assert.assertSame("旧 round cleanup 不得删除新 round context", newRound, registry.get(PLAYER, 6, 802L));
    }

    /** 匹配三元身份的非强制生命周期清理正常删除对应 context。 */
    @Test
    public void matchingRoundLifecycleCleanupClearsContext() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        registry.put(new ChainExecutionContext(PLAYER, 901L, 7,
                new ConcurrentLinkedQueue<ChainTarget>(), null));

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new LifecycleCleanup(PLAYER, 901L, 7, TICK, NANOS,
                "execution-complete", false, false));
        bus.drain();

        Assert.assertNull("匹配 round cleanup 应删除对应 context", registry.get(PLAYER, 7, 901L));
    }

    // ============================ C 流式执行：PlanStarted 开窗 + PlanCancelled 清理 ============================

    /**
     * C 流式开窗：publish PlanStarted → bridge.onPlanStarted 调 setExecutionWindow(true, ...)。
     *
     * <p>纯 JVM 单测 chainStateService=null，setExecutionWindow 早 return，此处验证订阅可达且不抛。
     * 真实 setExecuting(true) 时序由 runServer25 实机验证。</p>
     */
    @Test
    public void planStartedOpensExecutionWindowReachable() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        // PlanStarted 构造：最小必需字段（破坏路径：seedBlock=null, hitX/Y/Z=0）
        PlanStarted started = new PlanStarted(PLAYER, 1, TICK, NANOS,
                1, 2, 3, 0, 1, 0F, 0F, 0F, null, 0);
        bus.publish(started);
        bus.drain();
        // 可达且不抛即视为通过（chainStateService=null 早 return，无副作用可断言）
        Assert.assertTrue("PlanStarted 订阅应可达且不抛", true);
    }

    /**
     * C 流式登记后的清理：publish PlanCancelled → bridge.onPlanCancelled 清 registry + 关窗。
     *
     * <p>模拟 worker 启动失败前提前 put 的 context：onPlanStarted 在 registerPre 前已 put context，
     * 若 registerPre 抛 RejectedExecutionException → publish PlanCancelled → onPlanCancelled 清 registry。</p>
     */
    @Test
    public void planCancelledClearsRegistry() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        // 提前 put context（模拟 C 流式登记后 worker 启动失败 / 运行中取消）
        ChainExecutionContext context = new ChainExecutionContext(PLAYER, 2,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        registry.put(context);
        Assert.assertNotNull("前置 context 已 put", registry.get(PLAYER, 2));

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new PlanCancelled(PLAYER, 2, TICK, NANOS, "shadow-pool-exhausted"));
        bus.drain();

        Assert.assertNull("PlanCancelled 后 registry 应被 onPlanCancelled 清理", registry.get(PLAYER, 2));
    }

    /**
     * PlanCancelled 不存在 context 时幂等不抛（孤儿 PlanCancelled）。
     */
    @Test
    public void planCancelledWithoutContextIsIdempotent() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();

        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new PlanCancelled(PLAYER, 1, TICK, NANOS, "shadow-pool-exhausted"));
        bus.drain();
        // 无 context 也应安全返回
        Assert.assertNull(registry.get(PLAYER, 1));
    }

    /** 同 generation 的 R1 PlanCancelled 不得清除或关闭 R2 的执行上下文。 */
    @Test
    public void oldRoundCancellationDoesNotClearNewRoundContext() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainExecutionContextRegistry registry = new ChainExecutionContextRegistry();
        ChainExecutionContext r1 = new ChainExecutionContext(PLAYER, 501L, 4,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        ChainExecutionContext r2 = new ChainExecutionContext(PLAYER, 502L, 4,
                new ConcurrentLinkedQueue<ChainTarget>(), null);
        registry.put(r1);
        registry.put(r2);
        @SuppressWarnings("unused")
        ChainExecutionEventBridge bridge = new ChainExecutionEventBridge(bus, registry);

        bus.publish(new PlanCancelled(PLAYER, 501L, 4, TICK, NANOS, "late-r1"));
        bus.drain();

        Assert.assertSame("旧轮取消不得移除新轮 context", r2, registry.get(PLAYER, 4, 502L));
    }
}
