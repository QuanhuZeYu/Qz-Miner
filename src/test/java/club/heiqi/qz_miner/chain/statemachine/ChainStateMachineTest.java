package club.heiqi.qz_miner.chain.statemachine;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.ModeSwitched;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * {@link ChainStateMachine} 转移表与代际陈旧判定单测。
 *
 * <p>纯 JVM 逻辑测试：不实例化任何 {@code GuiScreen} 子类，不触碰 GL/LWJGL。
 * 通过 {@link ChainEventBus#publish} + {@link ChainEventBus#drain} 驱动，
 * 断言 {@link ChainStateMachine#getCurrentPhase()} 与 {@link ChainStateMachine#getCurrentGeneration()}。</p>
 *
 * <p>注意：本测试中 {@code bus.drain()} 会在软校验中 warn 主线程不匹配，属预期日志噪声，不影响断言。</p>
 */
public class ChainStateMachineTest {

    /** 测试固定使用的玩家 UUID。 */
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** 测试固定服务端 tick。 */
    private static final long TICK = 42L;
    /** 测试固定纳秒戳。 */
    private static final long NANOS = 123456789L;

    // ============================ 事件构造 helper ============================

    private ChainKeyPressed key(boolean pressed) {
        return key(0, pressed);
    }

    private ChainKeyPressed key(int gen, boolean pressed) {
        return new ChainKeyPressed(PLAYER, gen, TICK, NANOS, pressed);
    }

    private BlockBreakObserved breakObserved(int gen) {
        return new BlockBreakObserved(PLAYER, gen, TICK, NANOS, 1, 2, 3, 0, 1);
    }

    private ModeSwitched modeSwitched(int gen) {
        return new ModeSwitched(PLAYER, gen, TICK, NANOS, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE);
    }

    private PlanCompleted planCompleted(int gen) {
        return new PlanCompleted(PLAYER, gen, TICK, NANOS, 64);
    }

    private PlanCancelled planCancelled(int gen) {
        return new PlanCancelled(PLAYER, gen, TICK, NANOS, "test-cancel");
    }

    private ExecutionFinished execFinished(int gen) {
        return new ExecutionFinished(PLAYER, gen, TICK, NANOS, "done");
    }

    private WatchdogTimeout watchdog(int gen) {
        return new WatchdogTimeout(PLAYER, gen, TICK, NANOS, 1_000_000L);
    }

    private LifecycleCleanup cleanup(int gen) {
        return new LifecycleCleanup(PLAYER, gen, TICK, NANOS, "test-cleanup");
    }

    /** 新建一组 bus + sm，绑定当前线程作为主线程软校验锚。 */
    private static Harness newHarness() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainStateMachine sm = new ChainStateMachine(bus);
        return new Harness(bus, sm);
    }

    /** 发布事件并立刻 drain（主线程同步语义）。 */
    private static void drive(Harness h, club.heiqi.qz_miner.chain.eventbus.ChainEvent e) {
        h.bus.publish(e);
        h.bus.drain();
    }

    /** 测试用装具：持有 bus 与 sm 引用，方便断言与驱动。 */
    private static final class Harness {
        final ChainEventBus bus;
        final ChainStateMachine sm;

        Harness(ChainEventBus bus, ChainStateMachine sm) {
            this.bus = bus;
            this.sm = sm;
        }
    }

    // ============================ 1-11：单项合法转移 ============================

    /** 1. 初始态为 IDLE，代际为 0。 */
    @Test
    public void initialPhaseIsIdle() {
        Harness h = newHarness();
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 2. IDLE → ARMED：按下连锁键。 */
    @Test
    public void idleToArmedOnKeyPressed() {
        Harness h = newHarness();
        drive(h, key(true));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 3. ARMED → IDLE：松开连锁键。 */
    @Test
    public void armedToIdleOnKeyReleased() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, key(false));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 4. ARMED → IDLE：模式切换。 */
    @Test
    public void armedToIdleOnModeSwitched() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, modeSwitched(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 5. ARMED → PLANNING：破坏方块观测，代际 0 → 1。 */
    @Test
    public void armedToPlanningOnBreakBumpsGen() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 6. PLANNING → RUNNING：PlanCompleted（gen 匹配当前代际）。 */
    @Test
    public void planningToRunningOnPlanCompleted() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 7. PLANNING → IDLE：PlanCancelled。 */
    @Test
    public void planningToIdleOnPlanCancelled() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCancelled(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 8. RUNNING → FINISHING：ExecutionFinished。 */
    @Test
    public void runningToFinishingOnExecFinished() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 9. RUNNING → IDLE：WatchdogTimeout（T10 兜底）。 */
    @Test
    public void runningToIdleOnWatchdog() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, watchdog(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 10. RUNNING → IDLE：LifecycleCleanup（T9 兜底）。 */
    @Test
    public void runningToIdleOnLifecycleCleanup() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 11. FINISHING → IDLE：LifecycleCleanup（T8）。 */
    @Test
    public void finishingToIdleOnLifecycleCleanup() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    // ============================ 12：完整快乐路径 ============================

    /** 12. 全程 IDLE→ARMED→PLANNING→RUNNING→FINISHING→IDLE，gen 0→1。 */
    @Test
    public void fullHappyPathAllFiveReachable() {
        Harness h = newHarness();
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());

        drive(h, key(true));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase());

        drive(h, breakObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());

        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase());

        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase());

        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    // ============================ 13-17：越界事件不改态 ============================

    /** 13. IDLE 收 PlanCompleted 不变（越界丢弃）。 */
    @Test
    public void illegalIdleOnPlanCompletedStays() {
        Harness h = newHarness();
        drive(h, planCompleted(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 14. IDLE 收 ExecutionFinished 不变。 */
    @Test
    public void illegalIdleOnExecFinishedStays() {
        Harness h = newHarness();
        drive(h, execFinished(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 15. ARMED 收 PlanCompleted 不变。 */
    @Test
    public void illegalArmedOnPlanCompletedStays() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, planCompleted(0));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 16. RUNNING 收 ChainKeyPressed(true) 不变（Running 不该回 ARMED）。 */
    @Test
    public void illegalRunningOnKeyPressedStays() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, key(true));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 17. FINISHING 收 PlanCompleted 不变。 */
    @Test
    public void illegalFinishingOnPlanCompletedStays() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    // ============================ 18-20：代际陈旧判定 ============================

    /** 18. 陈旧代际 PlanCompleted（gen=0 < current=1）被丢弃，态不变。 */
    @Test
    public void staleGenPlanCompletedDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
        drive(h, planCompleted(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 19. 匹配代际 PlanCompleted（gen=1）正常转移。 */
    @Test
    public void matchingGenPlanCompletedProcessed() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 20. 未来代际 PlanCompleted（gen=99）不转移并告警。 */
    @Test
    public void futureGenPlanCompletedWarns() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(99));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    // ============================ 21-23：T9/T10 兜底 ============================

    /** 21. LifecycleCleanup 从 ARMED 兜底（T9）。 */
    @Test
    public void lifecycleCleanupFromArmed() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, cleanup(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(0, h.sm.getCurrentGeneration());
    }

    /** 22. LifecycleCleanup 从 PLANNING 兜底（T9）。 */
    @Test
    public void lifecycleCleanupFromPlanning() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }

    /** 23. WatchdogTimeout 从 PLANNING 兜底（T10，ARMED 不纳入 T10）。 */
    @Test
    public void watchdogFromPlanning() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, watchdog(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase());
        Assert.assertEquals(1, h.sm.getCurrentGeneration());
    }
}