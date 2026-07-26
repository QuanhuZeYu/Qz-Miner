package club.heiqi.qz_miner.chain.statemachine;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LeftClickObserved;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.ModeSwitched;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.eventbus.event.RightClickObserved;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.compat.adapter.TileIdentityToken;
import net.minecraft.init.Blocks;

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
        return new BlockBreakObserved(player, gen, TICK, NANOS, 1, 2, 3, 0, 1, null, 0);
    }

    private RightClickObserved rightClickObserved(int gen) {
        return rightClickObserved(PLAYER_A, gen);
    }

    private RightClickObserved rightClickObserved(UUID player, int gen) {
        return new RightClickObserved(player, gen, TICK, NANOS, 1, 2, 3, 0, 1, 0.5F, 0.5F, 0.5F);
    }

    private LeftClickObserved leftClickObserved(int gen) {
        return leftClickObserved(PLAYER_A, gen);
    }

    private LeftClickObserved leftClickObserved(UUID player, int gen) {
        // GT 线缆路径 hitX/Y/Z 默认 0（1.7.10 PlayerInteractEvent 左键分支未暴露命中偏移）
        return new LeftClickObserved(player, gen, TICK, NANOS, 1, 2, 3, 0, 1, 0.0F, 0.0F, 0.0F);
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

    /** F.1 W1 forced=true LifecycleCleanup（gen 不匹配也强制回 IDLE）。 */
    private LifecycleCleanup forcedCleanup(UUID player, int gen, boolean removeSlot) {
        return new LifecycleCleanup(player, gen, TICK, NANOS, "forced-test", true, removeSlot);
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

    /** 带轮次的 PlanCancelled 收口后，派生 IDLE phase 必须复制同一不可变关联。 */
    @Test
    public void planningCancellationCopiesRoundToIdlePhase() {
        long serverRoundId = 707L;
        Harness h = newHarness();
        List<ChainPhaseChanged> captured = new ArrayList<ChainPhaseChanged>();
        h.bus.subscribe(ChainPhaseChanged.class, captured::add);
        drive(h, key(true));
        drive(h, breakObserved(0));
        captured.clear();

        drive(h, new PlanCancelled(PLAYER_A, serverRoundId, 1, TICK, NANOS, "shadow-runtime-null"));

        Assert.assertEquals(1, captured.size());
        ChainPhaseChanged idlePhase = captured.get(0);
        Assert.assertEquals(ChainPhase.PLANNING, idlePhase.getFromPhase());
        Assert.assertEquals(ChainPhase.IDLE, idlePhase.getToPhase());
        Assert.assertEquals(serverRoundId, idlePhase.getServerRoundId());
        Assert.assertEquals(1, idlePhase.getGeneration());
    }

    /** 旧调用方的 round=0 PlanCancelled 仍按兼容语义派生 round=0 的 IDLE phase。 */
    @Test
    public void legacyPlanningCancellationKeepsZeroRoundOnIdlePhase() {
        Harness h = newHarness();
        List<ChainPhaseChanged> captured = new ArrayList<ChainPhaseChanged>();
        h.bus.subscribe(ChainPhaseChanged.class, captured::add);
        drive(h, key(true));
        drive(h, breakObserved(0));
        captured.clear();

        drive(h, planCancelled(1));

        Assert.assertEquals(1, captured.size());
        ChainPhaseChanged idlePhase = captured.get(0);
        Assert.assertEquals(ChainPhase.IDLE, idlePhase.getToPhase());
        Assert.assertEquals(ChainEvent.NO_SERVER_ROUND_ID, idlePhase.getServerRoundId());
        Assert.assertEquals(1, idlePhase.getGeneration());
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

    /**
     * 阶段8 D1：LeftClickObserved→T4 + gen++：ARMED 下左键观测（GT 线缆替换模式专用），
     * 对称破坏观测/右键观测，三事件入口之一。
     */
    @Test
    public void leftClickArmedToPlanningBumpsGen() {
        Harness h = newHarness();
        drive(h, key(PLAYER_A, 0, true));
        drive(h, leftClickObserved(PLAYER_A, 0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
        // 左键进入 PLANNING 后，派生事件按新 gen 正常推进（与破坏/右键入口对称）
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
    }

    /**
     * 阶段8 D1：LeftClickObserved 在 IDLE 越界丢弃（与破坏/右键入口对称）。
     */
    @Test
    public void leftClickInIdleIsDropped() {
        Harness h = newHarness();
        drive(h, leftClickObserved(PLAYER_A, 0));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /**
     * 阶段8 D1：LeftClickObserved 在 PLANNING 越界丢弃（与破坏/右键入口 P2-A 对称）。
     */
    @Test
    public void leftClickInPlanningDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, leftClickObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        drive(h, leftClickObserved(1));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /**
     * 阶段8 D1：B3 PlanStarted 进态广播——左键路径（GT 线缆替换）携带 hitX/Y/Z=0（默认值）。
     *
     * <p>对照 {@link #planStartedPublishedOnBreakWithOrigin}（hitX/Y/Z=0）与
     * {@link #planStartedPublishedOnRightClickWithHitOffset}（hitX/Y/Z 实际值）。
     * GT 线缆左键路径 1.7.10 未暴露命中偏移，填 0，flood fill 不依赖此值。</p>
     */
    @Test
    public void planStartedPublishedOnLeftClickWithZeroHitOffset() {
        Harness h = newHarness();
        List<PlanStarted> captured = new ArrayList<PlanStarted>();
        h.bus.subscribe(PlanStarted.class, captured::add);
        LeftClickObserved lcEvent = new LeftClickObserved(
                PLAYER_A, 0, TICK, NANOS, 11, 22, 33, 5, 2, 0.0F, 0.0F, 0.0F);
        drive(h, key(true));
        drive(h, lcEvent);
        Assert.assertEquals("应 publish 一条 PlanStarted", 1, captured.size());
        PlanStarted ps = captured.get(0);
        Assert.assertEquals(1, ps.getGeneration());
        Assert.assertEquals(11, ps.getX());
        Assert.assertEquals(22, ps.getY());
        Assert.assertEquals(33, ps.getZ());
        Assert.assertEquals(5, ps.getDimensionId());
        Assert.assertEquals(2, ps.getSideHit());
        // GT 线缆左键路径命中偏移默认 0
        Assert.assertEquals(0.0F, ps.getHitX(), 0.0F);
        Assert.assertEquals(0.0F, ps.getHitY(), 0.0F);
        Assert.assertEquals(0.0F, ps.getHitZ(), 0.0F);
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

    // ============================ 30+：阶段4 P2-A 越界 + PlanStarted 进态广播 ============================

    /**
     * 阶段4 P2-A：BlockBreakObserved 在 PLANNING 越界丢弃（破坏中再触发破坏不应改态）。
     * 锁定"破坏观测事件只在 ARMED 合法"的对称性。
     */
    @Test
    public void p2aBreakInPlanningDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        // PLANNING 下再发破坏观测，应越界丢弃
        drive(h, breakObserved(1));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 阶段4 P2-A：BlockBreakObserved 在 RUNNING 越界丢弃。 */
    @Test
    public void p2aBreakInRunningDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        drive(h, breakObserved(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 阶段4 P2-A：BlockBreakObserved 在 FINISHING 越界丢弃。 */
    @Test
    public void p2aBreakInFinishingDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
        drive(h, breakObserved(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
    }

    /** 阶段4 P2-A：RightClickObserved 在 PLANNING 越界丢弃。 */
    @Test
    public void p2aRightClickInPlanningDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, rightClickObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        drive(h, rightClickObserved(1));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /** 阶段4 P2-A：RightClickObserved 在 RUNNING 越界丢弃。 */
    @Test
    public void p2aRightClickInRunningDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, rightClickObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        drive(h, rightClickObserved(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
    }

    /** 阶段4 P2-A：RightClickObserved 在 FINISHING 越界丢弃。 */
    @Test
    public void p2aRightClickInFinishingDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, rightClickObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
        drive(h, rightClickObserved(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
    }

    // ============================ gen 竞态回归 ============================

    /**
     * gen 竞态回归：ARMED→break(gen=0) 进 PLANNING gen=1，
     * 再 publish PlanCompleted(gen=0)（模拟 worker 读旧 gen 早于状态机自增）→ 断言仍 PLANNING。
     *
     * <p>锁定"worker 实时读 gen 会死"根因：状态机 genCheck 丢弃陈旧 gen 的派生事件，
     * 故阶段4 gen 必须经 PlanStarted 注入 worker 闭包而非 worker 实时读状态机。</p>
     */
    @Test
    public void genRaceStalePlanCompletedDropped() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
        // worker 若读旧 gen=0 publish，状态机 genCheck 判定 0 < 1 陈旧丢弃
        drive(h, planCompleted(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
        // 对照：匹配 gen=1 的 PlanCompleted 正常推进
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
    }

    // ============================ PlanStarted 进态广播（B3） ============================

    /**
     * B3：break 路径进 PLANNING 后状态机 publish PlanStarted(gen=1)，携带 origin/sideHit，hitX/Y/Z=0。
     */
    @Test
    public void planStartedPublishedOnBreakWithOrigin() {
        Harness h = newHarness();
        List<PlanStarted> captured = new ArrayList<PlanStarted>();
        h.bus.subscribe(PlanStarted.class, captured::add);
        // 用带具体字段的破坏事件驱动
        BlockBreakObserved breakEvent = new BlockBreakObserved(
                PLAYER_A, 0, TICK, NANOS, 10, 20, 30, 7, 3, null, 0);
        drive(h, key(true));
        drive(h, breakEvent);
        Assert.assertEquals("应 publish 一条 PlanStarted", 1, captured.size());
        PlanStarted ps = captured.get(0);
        Assert.assertEquals(PLAYER_A, ps.getPlayerUUID());
        Assert.assertEquals("gen 应为状态机自增后的新值", 1, ps.getGeneration());
        Assert.assertEquals(10, ps.getX());
        Assert.assertEquals(20, ps.getY());
        Assert.assertEquals(30, ps.getZ());
        Assert.assertEquals(7, ps.getDimensionId());
        Assert.assertEquals(3, ps.getSideHit());
        // 破坏路径无命中偏移
        Assert.assertEquals(0.0F, ps.getHitX(), 0.0F);
        Assert.assertEquals(0.0F, ps.getHitY(), 0.0F);
        Assert.assertEquals(0.0F, ps.getHitZ(), 0.0F);
    }

    /** BreakEvent 捕获的纯值身份必须经状态机原样传播到 PlanStarted。 */
    @Test
    public void breakSeedTileIdentityPropagatesUnchangedToPlanStarted() {
        Harness h = newHarness();
        List<PlanStarted> captured = new ArrayList<PlanStarted>();
        h.bus.subscribe(PlanStarted.class, captured::add);
        TileIdentityToken token = TileIdentityToken.present("runtime-class", "fixture.Tile", "runtime-type");
        BlockBreakObserved breakEvent = new BlockBreakObserved(
                PLAYER_A, 77L, 0, TICK, NANOS, 10, 20, 30, 7, 3, null, 2, token);

        drive(h, key(true));
        drive(h, breakEvent);

        Assert.assertEquals(1, captured.size());
        Assert.assertSame("不可变 token 应原样传播，不得重新捕获", token,
                captured.get(0).getSeedTileIdentity());
    }

    /** 旧事件构造器缺少身份事实时必须默认 UNRESOLVED，而不是 ABSENT。 */
    @Test
    public void legacySeedEventConstructorsDefaultToUnresolved() {
        BlockBreakObserved observed = new BlockBreakObserved(
                PLAYER_A, 0, TICK, NANOS, 1, 2, 3, 0, 1, null, 0);
        RightClickObserved rightClick = new RightClickObserved(
                PLAYER_A, 0, TICK, NANOS, 1, 2, 3, 0, 1, 0.25F, 0.5F, 0.75F);
        PlanStarted started = new PlanStarted(
                PLAYER_A, 1, TICK, NANOS, 1, 2, 3, 0, 1, 0F, 0F, 0F, null, 0);

        Assert.assertSame(TileIdentityToken.unresolved(), observed.getSeedTileIdentity());
        Assert.assertSame(TileIdentityToken.unresolved(), rightClick.getSeedTileIdentity());
        Assert.assertSame(TileIdentityToken.unresolved(), started.getSeedTileIdentity());
    }

    /** 右键事件必须保留完整非负 int metadata 与不可变种子引用，不得截断或重捕获。 */
    @Test
    public void rightClickObservedFreezesFullSeedFacts() {
        TileIdentityToken token = TileIdentityToken.present("fixture", "fixture.Tile", "seed-key");
        RightClickObserved observed = new RightClickObserved(
                PLAYER_A, 88L, 0, TICK, NANOS, 11, 22, 33, 5, 2,
                0.25F, 0.5F, 0.75F, Blocks.stone, Integer.MAX_VALUE, token);

        Assert.assertSame(Blocks.stone, observed.getSeedBlock());
        Assert.assertEquals("metadata 不得套 16-bit 或 vanilla 上限", Integer.MAX_VALUE, observed.getSeedMeta());
        Assert.assertSame(token, observed.getSeedTileIdentity());

        RightClickObserved malformed = new RightClickObserved(
                PLAYER_A, 0, TICK, NANOS, 1, 2, 3, 0, 1,
                0F, 0F, 0F, Blocks.stone, -1, null);
        Assert.assertEquals("事件边界不得传播负 metadata", 0, malformed.getSeedMeta());
        Assert.assertSame("null token 必须 fail-closed", TileIdentityToken.unresolved(),
                malformed.getSeedTileIdentity());
    }

    /** 右键 seed 三字段必须是构造后不可改的 final 字段。 */
    @Test
    public void rightClickSeedFieldsAreImmutable() throws Exception {
        Assert.assertTrue(java.lang.reflect.Modifier.isFinal(
                RightClickObserved.class.getDeclaredField("seedBlock").getModifiers()));
        Assert.assertTrue(java.lang.reflect.Modifier.isFinal(
                RightClickObserved.class.getDeclaredField("seedMeta").getModifiers()));
        Assert.assertTrue(java.lang.reflect.Modifier.isFinal(
                RightClickObserved.class.getDeclaredField("seedTileIdentity").getModifiers()));
    }

    /** 原右键窗口冻结的 block/meta/token 必须经状态机原样传播到 PlanStarted。 */
    @Test
    public void rightClickSeedFactsPropagateUnchangedToPlanStarted() {
        Harness h = newHarness();
        List<PlanStarted> captured = new ArrayList<PlanStarted>();
        h.bus.subscribe(PlanStarted.class, captured::add);
        TileIdentityToken token = TileIdentityToken.present("fixture", "fixture.Tile", "right-click-seed");
        RightClickObserved rightClick = new RightClickObserved(
                PLAYER_A, 89L, 0, TICK, NANOS, 11, 22, 33, 5, 2,
                0.25F, 0.5F, 0.75F, Blocks.lit_redstone_ore, Integer.MAX_VALUE, token);

        drive(h, key(true));
        drive(h, rightClick);

        Assert.assertEquals(1, captured.size());
        PlanStarted started = captured.get(0);
        Assert.assertEquals(89L, started.getServerRoundId());
        Assert.assertSame(Blocks.lit_redstone_ore, started.getSeedBlock());
        Assert.assertEquals(Integer.MAX_VALUE, started.getSeedMeta());
        Assert.assertSame("状态机不得重新读取或重建 token", token, started.getSeedTileIdentity());
    }

    /**
     * B3：右键路径进 PLANNING 后状态机 publish PlanStarted，hitX/Y/Z 携带实际值。
     */
    @Test
    public void planStartedPublishedOnRightClickWithHitOffset() {
        Harness h = newHarness();
        List<PlanStarted> captured = new ArrayList<PlanStarted>();
        h.bus.subscribe(PlanStarted.class, captured::add);
        RightClickObserved rcEvent = new RightClickObserved(
                PLAYER_A, 0, TICK, NANOS, 11, 22, 33, 5, 2, 0.25F, 0.5F, 0.75F);
        drive(h, key(true));
        drive(h, rcEvent);
        Assert.assertEquals("应 publish 一条 PlanStarted", 1, captured.size());
        PlanStarted ps = captured.get(0);
        Assert.assertEquals(1, ps.getGeneration());
        Assert.assertEquals(11, ps.getX());
        Assert.assertEquals(22, ps.getY());
        Assert.assertEquals(33, ps.getZ());
        Assert.assertEquals(5, ps.getDimensionId());
        Assert.assertEquals(2, ps.getSideHit());
        // 右键路径携带实际命中偏移
        Assert.assertEquals(0.25F, ps.getHitX(), 0.0F);
        Assert.assertEquals(0.5F, ps.getHitY(), 0.0F);
        Assert.assertEquals(0.75F, ps.getHitZ(), 0.0F);
    }

    /**
     * B3：IDLE/越界态下破坏观测不发 PlanStarted（只合法转移才广播）。
     */
    @Test
    public void planStartedNotPublishedWhenTransitionIllegal() {
        Harness h = newHarness();
        List<PlanStarted> captured = new ArrayList<PlanStarted>();
        h.bus.subscribe(PlanStarted.class, captured::add);
        // IDLE 下破坏观测越界丢弃，不发 PlanStarted
        drive(h, breakObserved(0));
        Assert.assertTrue("越界丢弃不应 publish PlanStarted", captured.isEmpty());
    }

    // ============================ 阶段5：执行接入卡点回归 ============================

    /**
     * 卡点6 回归：RUNNING(gen=1) 收陈旧 gen ExecutionFinished(gen=0) → 丢弃不转移。
     *
     * <p>模拟执行中玩家重按键触发新一代规划（worker 已被新一代取代），
     * 迟到的旧 gen ExecutionFinished 被状态机 genCheck 丢弃，不切 FINISHING。
     * 锁定"gen 传递链保护"——ExecutionFinished 的 gen 必须来自 ExecutionContext
     * （经 PlanCompleted 注入），绝不能实时读状态机 generation（否则竞态下 genCheck 误判）。</p>
     *
     * <p>对照阶段4 {@link #genRaceStalePlanCompletedDropped()}：陈旧 PlanCompleted 在 PLANNING 被丢弃，
     * 本用例验证陈旧 ExecutionFinished 在 RUNNING 被丢弃（对称防护）。</p>
     */
    @Test
    public void staleExecutionFinishedInRunningDropped() {
        Harness h = newHarness();
        // IDLE → ARMED → PLANNING → RUNNING(gen=1)
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // 假设迟到的旧 worker publish 了陈旧 gen=0 的 ExecutionFinished（worker 早于状态机 ++gen 读到旧值）
        drive(h, execFinished(0));
        Assert.assertEquals("陈旧 gen ExecutionFinished 应被丢弃，态不变",
                ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // 对照：匹配 gen=1 的 ExecutionFinished 正常 T7 RUNNING→FINISHING
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
    }

    /**
     * 卡点6 对称防护：FINISHING(gen=1) 收陈旧 gen=0 LifecycleCleanup → 丢弃不转移。
     *
     * <p>验证 E4-b 临时 LifecycleCleanup 桥走 T8 时也受 genCheck 保护，
     * 陈旧 gen 不会误触发 T8 FINISHING→IDLE。</p>
     */
    @Test
    public void staleLifecycleCleanupInFinishingDropped() {
        Harness h = newHarness();
        // IDLE → ARMED → PLANNING → RUNNING → FINISHING(gen=1)
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));

        // 陈旧 gen=0 LifecycleCleanup 应被丢弃
        drive(h, cleanup(0));
        Assert.assertEquals("陈旧 gen LifecycleCleanup 应被丢弃，态不变",
                ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));

        // 匹配 gen=1 LifecycleCleanup 正常 T8 FINISHING→IDLE（E4-b 桥验证）
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
    }

    /**
     * E4-b 完整闭环回归：T5→T7→T8 全链路，模拟执行订阅者 publish 的两条事件
     * （ExecutionFinished + 临时 LifecycleCleanup）能驱动状态机从 RUNNING 经 FINISHING 回 IDLE。
     *
     * <p>本用例对齐 {@code ChainExecutionEventBridge.publishExecutionFinishedWithCleanup} 的两条 publish，
     * 验证状态机两次 drain（同 tick 紧接）能完成 T7+T8 合法转移，玩家槽不留卡 FINISHING。</p>
     */
    @Test
    public void phase5TemporaryCleanupBridgeDrivesFullT7T8Chain() {
        Harness h = newHarness();
        // IDLE → ARMED → PLANNING → RUNNING(gen=1)
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));

        // 模拟执行订阅者 publishExecutionFinishedWithCleanup：两条事件入队，drain 顺序处理
        // gen=1 必须来自 ExecutionContext（经 PlanCompleted 注入），事件流回填
        h.bus.publish(new ExecutionFinished(PLAYER_A, 1, TICK, NANOS, "executor-consumed-all-targets"));
        h.bus.publish(new LifecycleCleanup(PLAYER_A, 1, TICK, NANOS, "phase5-temporary-cleanup-bridge"));
        h.bus.drain();

        // T7 RUNNING→FINISHING → T8 FINISHING→IDLE
        Assert.assertEquals("E4-b 桥应驱动状态机回 IDLE，避免玩家槽卡 FINISHING 致二次连锁哑火",
                ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // 验证后续连锁能正常触发（玩家槽已回 IDLE，T1 合法）
        drive(h, key(true));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase(PLAYER_A));
    }

    // ============================ 阶段7：F.1 W1 + F.2 S1 ============================

    /**
     * 阶段7 F.1 W1：forced=true LifecycleCleanup 豁免 genCheck。
     *
     * <p>slot gen=5（RUNNING），event gen=999 forced=true → 强制回 IDLE。
     * 守 I7：玩家都登出了，哪一代都得清；跨包拿不到 slot.generation，强制清理不该受代际约束。</p>
     */
    @Test
    public void forcedLifecycleCleanupSkipsGenCheck() {
        Harness h = newHarness();
        // IDLE → ARMED → PLANNING → RUNNING(gen=1)，再手动用 gen=2 进 PLANNING 模拟 gen=5 场景
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // forced=true + gen=999（明显不匹配 slot gen=1）→ 应豁免 genCheck 强制回 IDLE
        drive(h, forcedCleanup(PLAYER_A, 999, false));
        Assert.assertEquals("F.1 W1：forced LifecycleCleanup 应豁免 genCheck 强制回 IDLE",
                ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        // removeSlot=false 保槽，gen 不变
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /**
     * 阶段7 F.1 对照：forced=false 走 genCheck（陈旧 gen 被丢弃）。
     *
     * <p>对照 {@link #forcedLifecycleCleanupSkipsGenCheck}：同一 gen=999 但 forced=false → 走 genCheck
     * 被判定未来 gen 丢弃，态不变。</p>
     */
    @Test
    public void nonForcedLifecycleCleanupGoesGenCheck() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));

        // forced=false（默认 5 参构造器）+ gen=999 → genCheck 判定未来 gen 丢弃
        LifecycleCleanup stale = new LifecycleCleanup(PLAYER_A, 999, TICK, NANOS, "non-forced-test");
        drive(h, stale);
        Assert.assertEquals("非 forced 走 genCheck，未来 gen 应被丢弃，态不变",
                ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
    }

    /**
     * 阶段7 F.2 S1：removeSlot=true 转移后 slots.remove（玩家槽被删）。
     *
     * <p>删槽后 getCurrentPhase 会重新 computeIfAbsent 返回默认 IDLE/gen=0。
     * 守 I10：slots.remove 唯一写权威在状态机 handler 内。</p>
     */
    @Test
    public void removeSlotTrueDeletesSlot() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        Assert.assertEquals(ChainPhase.RUNNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // forced=true + removeSlot=true → 回 IDLE 后删槽
        drive(h, forcedCleanup(PLAYER_A, 0, true));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        // 删槽后重新 computeIfAbsent，gen 重置为 0（槽被删）
        Assert.assertEquals("F.2 S1：removeSlot=true 应删槽，gen 重置", 0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /**
     * 阶段7 F.2 S1 对照：removeSlot=false 转移后 slots 保留（保 gen 单调）。
     *
     * <p>对照 {@link #removeSlotTrueDeletesSlot}：removeSlot=false → 槽保留，gen 不变。
     * RESPAWN/DIMENSION_CHANGE/CLONE/执行完成路径都走此分支保 gen 单调。</p>
     */
    @Test
    public void removeSlotFalseKeepsSlotAndGen() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // forced=true + removeSlot=false → 回 IDLE 但保槽保 gen
        drive(h, forcedCleanup(PLAYER_A, 0, false));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals("F.2 S1：removeSlot=false 应保槽保 gen 单调", 1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    // ============================ P2-1：IDLE 态 LOGOUT 删槽收口 ============================

    /**
     * P2-1 核心场景：玩家槽在 IDLE + LifecycleCleanup(forced=true, removeSlot=true) → 仍删槽。
     *
     * <p>原阶段7 实现只在非 IDLE 分支末尾 remove，IDLE early-return 命中后 LOGOUT 意图被吞 → 槽泄漏。
     * 本用例模拟常见登出场景（玩家完成连锁回 IDLE 后登出）：先走完整闭环回 IDLE，
     * 再发 forced LifecycleCleanup(removeSlot=true)，断言槽被删（getCurrentGeneration 重新 computeIfAbsent 返回 0）。</p>
     *
     * <p>守 I10：slots.remove 仍在状态机 handler 内（唯一写权威）。</p>
     */
    @Test
    public void removeSlotTrueDeletesSlotEvenInIdle() {
        Harness h = newHarness();
        // 完整闭环回 IDLE，gen=1
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        drive(h, cleanup(1));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals("前置：玩家槽在 IDLE 且 gen=1", 1, h.sm.getCurrentGeneration(PLAYER_A));

        // 模拟玩家登出：forced=true + removeSlot=true（IDLE 态的 LOGOUT 常见场景）
        drive(h, forcedCleanup(PLAYER_A, 999, true));

        // P2-1：IDLE 分支也应删槽
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals("P2-1：IDLE 态 LOGOUT 应删槽，getCurrentGeneration 重新 computeIfAbsent 返回默认 gen=0",
                0, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /**
     * P2-1 对照：玩家槽在 IDLE + LifecycleCleanup(forced=true, removeSlot=false) → 槽保留保 gen。
     *
     * <p>对照 {@link #removeSlotTrueDeletesSlotEvenInIdle}：IDLE 态下 removeSlot=false（如 RESPAWN/维度切换）
     * 不应误删槽，gen 保持不变（保 gen 单调）。</p>
     */
    @Test
    public void removeSlotFalseKeepsSlotInIdle() {
        Harness h = newHarness();
        // 完整闭环回 IDLE，gen=1
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        drive(h, cleanup(1));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // 模拟玩家重生：forced=true + removeSlot=false（IDLE 态的 RESPAWN）
        drive(h, forcedCleanup(PLAYER_A, 999, false));

        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals("P2-1 对照：IDLE 态 removeSlot=false 应保槽保 gen 单调",
                1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    // ============================ E2 松键即停：forced LifecycleCleanup 各活跃态强制回 IDLE ============================

    /**
     * E2 松键即停：PLANNING 态收 forced=true LifecycleCleanup → T9 兜底回 IDLE。
     *
     * <p>松键修复路径：PacketKeyState pressed=false 时 publish LifecycleCleanup(reason="user-abort",
     * forced=true, removeSlot=false)。worker 仍在影子遍历 PLANNING 期间，松键应能立即终止活跃连锁
     * （守 I7：哪一代都得清；复用 T9 PLANNING→IDLE 不改转移表）。</p>
     */
    @Test
    public void forcedLifecycleCleanupFromPlanningReturnsIdle() {
        Harness h = newHarness();
        drive(h, key(true));
        drive(h, breakObserved(0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // 模拟松键：forced=true + removeSlot=false（玩家在线保 gen 单调）
        drive(h, forcedCleanup(PLAYER_A, 1, false));
        Assert.assertEquals("E2：PLANNING 收 forced LifecycleCleanup 应走 T9 回 IDLE",
                ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals("removeSlot=false 保 gen 单调", 1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /**
     * E2 松键即停：FINISHING 态收 forced=true LifecycleCleanup → T8 回 IDLE。
     *
     * <p> ExecutionFinished 已 T7 RUNNING→FINISHING，但本桥 publish 的非 forced LifecycleCleanup
     * 迟到或玩家在此瞬间松键，forced 路径应能强制收口（守 I7）。</p>
     */
    @Test
    public void forcedLifecycleCleanupFromFinishingReturnsIdle() {
        Harness h = newHarness();
        // IDLE → ARMED → PLANNING → RUNNING → FINISHING(gen=1)
        drive(h, key(true));
        drive(h, breakObserved(0));
        drive(h, planCompleted(1));
        drive(h, execFinished(1));
        Assert.assertEquals(ChainPhase.FINISHING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        // 模拟松键：forced=true + removeSlot=false
        drive(h, forcedCleanup(PLAYER_A, 1, false));
        Assert.assertEquals("E2：FINISHING 收 forced LifecycleCleanup 应走 T8 回 IDLE",
                ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals("removeSlot=false 保 gen 单调", 1, h.sm.getCurrentGeneration(PLAYER_A));
    }

    /**
     * T1-T10 派生的 ChainPhaseChanged 与 T4 的 PlanStarted 必须复制触发事件的不可变轮次。
     */
    @Test
    public void allTransitionsCopyTriggerServerRoundId() {
        final long roundId = 808L;
        Harness h = newHarness();
        List<ChainEvent> captured = new ArrayList<ChainEvent>();
        h.bus.subscribe(ChainPhaseChanged.class, captured::add);
        h.bus.subscribe(PlanStarted.class, captured::add);

        drive(h, new ChainKeyPressed(PLAYER_A, roundId, 0, TICK, NANOS, true)); // T1
        drive(h, new ChainKeyPressed(PLAYER_A, roundId, 0, TICK, NANOS, false)); // T2
        drive(h, new ChainKeyPressed(PLAYER_A, roundId, 0, TICK, NANOS, true)); // T1
        drive(h, new ModeSwitched(PLAYER_A, roundId, 0, TICK, NANOS, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE)); // T3

        drive(h, new ChainKeyPressed(PLAYER_A, roundId, 0, TICK, NANOS, true));
        drive(h, new BlockBreakObserved(PLAYER_A, roundId, 0, TICK, NANOS, 1, 2, 3, 0, 1, null, 0)); // T4
        drive(h, new PlanCompleted(PLAYER_A, roundId, 1, TICK, NANOS, 1)); // T5
        drive(h, new ExecutionFinished(PLAYER_A, roundId, 1, TICK, NANOS, "done")); // T7
        drive(h, new LifecycleCleanup(PLAYER_A, roundId, 1, TICK, NANOS, "done", false, false)); // T8

        drive(h, new ChainKeyPressed(PLAYER_A, roundId, 1, TICK, NANOS, true));
        drive(h, new BlockBreakObserved(PLAYER_A, roundId, 1, TICK, NANOS, 1, 2, 3, 0, 1, null, 0));
        drive(h, new PlanCancelled(PLAYER_A, roundId, 2, TICK, NANOS, "cancel")); // T6

        drive(h, new ChainKeyPressed(PLAYER_A, roundId, 2, TICK, NANOS, true));
        drive(h, new BlockBreakObserved(PLAYER_A, roundId, 2, TICK, NANOS, 1, 2, 3, 0, 1, null, 0));
        drive(h, new WatchdogTimeout(PLAYER_A, roundId, 3, TICK, NANOS, 1L)); // T10

        drive(h, new ChainKeyPressed(PLAYER_A, roundId, 3, TICK, NANOS, true));
        drive(h, new LifecycleCleanup(PLAYER_A, roundId, 3, TICK, NANOS, "forced", true, false)); // T9

        Assert.assertFalse("应捕获状态机派生事件", captured.isEmpty());
        for (ChainEvent event : captured) {
            Assert.assertEquals("派生事件不得读取后来轮次", roundId, event.getServerRoundId());
        }
    }

    /** 新 round 的 fresh key 只能先合法武装，随后带新 round 的观测才进入规划。 */
    @Test
    public void freshKeyArmsBeforeSecondRoundObservationStartsPlanning() {
        final long firstRoundId = 901L;
        final long secondRoundId = 902L;
        Harness h = newHarness();
        drive(h, new ChainKeyPressed(PLAYER_A, firstRoundId, 0, TICK, NANOS, true));
        drive(h, new BlockBreakObserved(PLAYER_A, firstRoundId, 0, TICK, NANOS,
                1, 2, 3, 0, 1, null, 0));
        drive(h, new PlanCancelled(PLAYER_A, firstRoundId, 1, TICK, NANOS, "round-one-finished"));
        Assert.assertEquals(ChainPhase.IDLE, h.sm.getCurrentPhase(PLAYER_A));

        drive(h, new ChainKeyPressed(PLAYER_A, secondRoundId, 1, TICK, NANOS, true));
        Assert.assertEquals(ChainPhase.ARMED, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(1, h.sm.getCurrentGeneration(PLAYER_A));

        drive(h, new BlockBreakObserved(PLAYER_A, secondRoundId, 1, TICK, NANOS,
                4, 5, 6, 0, 1, null, 0));
        Assert.assertEquals(ChainPhase.PLANNING, h.sm.getCurrentPhase(PLAYER_A));
        Assert.assertEquals(2, h.sm.getCurrentGeneration(PLAYER_A));
    }
}
