package club.heiqi.qz_miner.chain.statemachine;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.ModeSwitched;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.RightClickObserved;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * {@link ChainStateMachine} 转移表与代际陈旧判定单测。
 *
 * <p>纯 JVM 逻辑测试：不实例化任何 {@code GuiScreen} 子类，不触碰 GL/LWJGL。
 * 通过 {@link ChainEventBus#publish} + {@link ChainEventBus#drain} 驱动，
 * 断言 {@link ChainStateMachine#getCurrentPhase(UUID)} 与 {@link ChainStateMachine#getCurrentGeneration(UUID)}。</p>
 *
 * <p>注意：{@code newHarness()} 已 {@code bindMainThread(Thread.currentThread())}，
 * drain 软校验不触发 warn，无预期日志噪声。</p>
 *
 * <p>阶段3起状态机 per-player 化（按玩家 UUID 分槽），事件 helper 支持
 * {@link #PLAYER_A}/{@link #PLAYER_B} 双玩家，断言带 UUID 参数。</p>
 */
public class ChainStateMachineTest {

    /** 测试固定使用的玩家 A。 */
    private static final UUID PLAYER_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** 测试固定使用的玩家 B（per-player 隔离用）。 */
    private static final UUID PLAYER_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** 测试固定服务端 tick。 */
    private static final long TICK = 42L;
    /** 测试固定纳秒戳。 */
    private static final long NANOS = 123456789L;

    // ============================ 事件构造 helper ============================

    private ChainKeyPressed key(boolean pressed) {
        return key(PLAYER_A, 0, pressed);
    }

    private ChainKeyPressed key(int gen, boolean pressed) {
        return key(PLAYER_A, gen, pressed);
    }

    private ChainKeyPressed key(UUID player, int gen, boolean pressed) {
        return new ChainKeyPressed(player, gen, TICK, NANOS, pressed);
    }

    private BlockBreakObserved breakObserved(int gen) {
        return breakObserved(PLAYER_A, gen);
    }

    private BlockBreakObserved breakObserved(UUID player, int gen) {
        return new BlockBreakObserved(player, gen, TICK, NANOS, 1, 2, 3, 0, 1);
    }

    private RightClickObserved rightClickObserved(int gen) {
        return rightClickObserved(PLAYER_A, gen);
    }

    private RightClickObserved rightClickObserved(UUID player, int gen) {
        return new RightClickObserved(player, gen, TICK, NANOS, 1, 2, 3, 0, 1, 0.5F, 0.5F, 0.5F);
    }

    private ModeSwitched modeSwitched(int gen) {
        return modeSwitched(PLAYER_A, gen);
    }

    private ModeSwitched modeSwitched(UUID player, int gen) {
        return new ModeSwitched(player, gen, TICK, NANOS, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE);
    }

    private PlanCompleted planCompleted(int gen) {
        return planCompleted(PLAYER_A, gen);
    }

    private PlanCompleted planCompleted(UUID player, int gen) {
        return new PlanCompleted(player, gen, TICK, NANOS, 64);
    }

    private PlanCancelled planCancelled(int gen) {
        return planCancelled(PLAYER_A, gen);
    }

    private PlanCancelled planCancelled(UUID player, int gen) {
        return new PlanCancelled(player, gen, TICK, NANOS, "test-cancel");
    }

    private ExecutionFinished execFinished(int gen) {
        return execFinished(PLAYER_A, gen);
    }

    private ExecutionFinished execFinished(UUID player, int gen) {
        return new ExecutionFinished(player, gen, TICK, NANOS, "done");
    }

    private WatchdogTimeout watchdog(int gen) {
        return watchdog(PLAYER_A, gen);
    }

    private WatchdogTimeout watchdog(UUID player, int gen) {
        return new WatchdogTimeout(player, gen, TICK, NANOS, 1_000_000L);
    }

    private LifecycleCleanup cleanup(int gen) {
        return cleanup(PLAYER_A, gen);
    }

    private LifecycleCleanup cleanup(UUID player, int gen) {
        return new LifecycleCleanup(player, gen, TICK, NANOS, "test-cleanup");
    }

    /** 新建一组 bus + sm，绑定当前线程作为主线程软校验锚。 */
    private static Harness newHarness() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainStateMachine sm = new ChainStateMachine(bus);
        return new Harness(bus, sm);
    }

    /** 发布事件并立刻 drain（主线程同步语义）。 */
    private static void drive(Harness h, ChainEvent e) {
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
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 2. IDLE → ARMED：按下连锁键。 */
    @Test
    public void idleToArmedOnKeyPressed() {
        Harness h = newHarness();
        drive(h, key(true));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 3. ARMED → IDLE：松开连锁键。 */
    @Test
    public void armedToIdleOnKeyReleased() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, key(false));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 4. ARMED → IDLE：模式切换。 */
    @Test
    public void armedToIdleOnModeSwitched() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, modeSwitched(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 5. ARMED → PLANNING：破坏方块观测，代际 0 → 1。 */
    @Test
    public void armedToPlanningOnBreakBumpsGen() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 6. PLANNING → RUNNING：PlanCompleted（gen 匹配当前代际）。 */
    @Test
    public void planningToRunningOnPlanCompleted() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 7. PLANNING → IDLE：PlanCancelled。 */
    @Test
    public void planningToIdleOnPlanCancelled() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCancelled(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 8. RUNNING → FINISHING：ExecutionFinished。 */
    @Test
    public void runningToFinishingOnExecFinished() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 9. RUNNING → IDLE：WatchdogTimeout（T10 兜底）。 */
    @Test
    public void runningToIdleOnWatchdog() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, watchdog(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 10. RUNNING → IDLE：LifecycleCleanup（T9 兜底）。 */
    @Test
    public void runningToIdleOnLifecycleCleanup() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
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
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    // ============================ 12：完整快乐路径 ============================

    /** 12. 全程 IDLE→ARMED→PLANNING→RUNNING→FINISHING→IDLE，gen 0→1。 */
    @Test
    public void fullHappyPathAllFiveReachable() {
        Harness h = newHarness();
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));

        drive(h, key(true));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase(PLAYER_A));

        drive(h, breakObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));

        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));

        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    // ============================ 13-17：越界事件不改态 ============================

    /** 13. IDLE 收 PlanCompleted 不变（越界丢弃）。 */
    @Test
    public void illegalIdleOnPlanCompletedStays() {
        Harness h = newHarness();
        drive(h, planCompleted(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 14. IDLE 收 ExecutionFinished 不变。 */
    @Test
    public void illegalIdleOnExecFinishedStays() {
        Harness h = newHarness();
        drive(h, execFinished(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 15. ARMED 收 PlanCompleted 不变。 */
    @Test
    public void illegalArmedOnPlanCompletedStays() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, planCompleted(0));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 16. RUNNING 收 ChainKeyPressed(true) 不变（Running 不该回 ARMED）。 */
    @Test
    public void illegalRunningOnKeyPressedStays() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, key(true));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
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
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    // ============================ 18-20：代际陈旧判定 ============================

    /** 18. 陈旧代际 PlanCompleted（gen=0 < current=1）被丢弃，态不变。 */
    @Test
    public void staleGenPlanCompletedDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
        drive(h, planCompleted(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 19. 匹配代际 PlanCompleted（gen=1）正常转移。 */
    @Test
    public void matchingGenPlanCompletedProcessed() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 20. 未来代际 PlanCompleted（gen=99）不转移并告警。 */
    @Test
    public void futureGenPlanCompletedWarns() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(99));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    // ============================ 21-24：T9/T10 兜底 ============================

    /** 21. LifecycleCleanup 从 ARMED 兜底（T9）。 */
    @Test
    public void lifecycleCleanupFromArmed() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, cleanup(0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 22. LifecycleCleanup 从 PLANNING 兜底（T9）。 */
    @Test
    public void lifecycleCleanupFromPlanning() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 23. WatchdogTimeout 从 PLANNING 兜底（T10，ARMED 不纳入 T10）。 */
    @Test
    public void watchdogFromPlanning() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, watchdog(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 24. WatchdogTimeout 从 FINISHING 兜底回 IDLE（T10，补 FINISHING→IDLE 源态覆盖）。 */
    @Test
    public void watchdogFromFinishing() {
        Harness h = newHarness();
        // IDLE → ARMED → PLANNING → RUNNING → FINISHING
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
        // FINISHING → IDLE via WatchdogTimeout（gen=1 匹配当前代际）
        drive(h, watchdog(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    // ============================ 25-29：阶段3 per-player + T4 右键 ============================

    /** 25. 双玩家不串台：A 武装后 A=ARMED，B 仍 IDLE；A 破坏后 A=PLANNING gen=1，B 不受影响。 */
    @Test
    public void perPlayerIsolationA() {
        Harness h = newHarness();
        drive(h, key(PLAYER_A, 0, true));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
        // B 应保持初始态，未被 A 武装波及
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_B));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_B));

        drive(h, breakObserved(PLAYER_A, 0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
        // B 仍不受 A 破坏影响
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_B));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_B));
    }

    /** 26. 并发挖矿隔离：B 未武装时收破坏事件越界丢弃，不推进 A。 */
    @Test
    public void perPlayerIsolationBNotArmedDrop() {
        Harness h = newHarness();
        // A 武装 + 破坏进入 PLANNING
        drive(h, key(PLAYER_A, 0, true));
        drive(h, breakObserved(PLAYER_A, 0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // B 未武装，破坏事件应越界丢弃，B 保持 IDLE gen=0
        drive(h, breakObserved(PLAYER_B, 0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_B));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_B));
        // A 不受 B 越界丢弃影响
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 27. RightClickObserved→T4 + gen++：ARMED 下右键观测，对称破坏观测。 */
    @Test
    public void rightClickArmedToPlanningBumpsGen() {
        Harness h = newHarness();
        drive(h, key(PLAYER_A, 0, true));
        drive(h, rightClickObserved(PLAYER_A, 0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
        // 右键进入 PLANNING 后，派生事件按新 gen 正常推进
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
    }

    /** 28. RightClickObserved 在 IDLE 越界丢弃（与 BlockBreakObserved 在 IDLE 一致）。 */
    @Test
    public void rightClickInIdleIsDropped() {
        Harness h = newHarness();
        drive(h, rightClickObserved(PLAYER_A, 0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));

        // 对照：BlockBreakObserved 在 IDLE 同样越界丢弃
        Harness h2 = newHarness();
        drive(h2, breakObserved(PLAYER_A, 0));
        Assert.assertEquals(ChainPhase.IDLE, h2.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h2.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 29. 不同玩家 gen 独立自增：A 两次触发 gen=2，B 一次触发 gen=1。 */
    @Test
    public void perPlayerGenerationIndependentIncrement() {
        Harness h = newHarness();
        // A 第一次：武装→破坏进入 PLANNING，gen=1
        drive(h, key(PLAYER_A, 0, true));
        drive(h, breakObserved(PLAYER_A, 0));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        // A 经 PlanCompleted/ExecutionFinished/LifecycleCleanup 回 IDLE 后再武装再破坏
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        drive(h, key(PLAYER_A, 0, true));
        drive(h, breakObserved(PLAYER_A, 0));
        // A 第二次自增到 gen=2
        Assert.assertEquals(2, h.sm.getCurrentGeneration(PLAYER_A));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));

        // B 独立自增：B 武装→破坏，gen 应为 1（不被 A 的 gen=2 影响）
        drive(h, key(PLAYER_B, 0, true));
        drive(h, breakObserved(PLAYER_B, 0));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_B));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_B));
        // A 仍是 gen=2
        Assert.assertEquals(2, h.sm.getCurrentGeneration(PLAYER_A));
    }
}