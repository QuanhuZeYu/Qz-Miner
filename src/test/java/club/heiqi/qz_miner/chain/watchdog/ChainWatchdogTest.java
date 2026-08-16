package club.heiqi.qz_miner.chain.watchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.eventbus.ChainEvent;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionAdvanced;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionDeferred;
import club.heiqi.qz_miner.chain.eventbus.event.PlanProgress;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * {@link ChainWatchdog} 单测。
 *
 * <p>纯 JVM 逻辑测试：不注册 FML bus（不调 {@link ChainWatchdog#bootstrap}），
 * 直接调 {@link ChainWatchdog#onServerTick} 驱动 tick。
 * 进态信号通过 {@link ChainEventBus#publish} + {@link ChainEventBus#drain} 触发
 * {@link ChainWatchdog#onPhaseChanged} 订阅者。</p>
 *
 * <p>F.3 A-armed-skip / F.4 C1 / gen 竞态 / currentTick&lt;0 跳过 全覆盖。</p>
 */
public class ChainWatchdogTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000AB");

    /** 新建 bus + watchdog（不 bootstrap，避免 FMLCommonHandler NPE）。 */
    private static Harness newHarness() {
        ChainEventBus bus = new ChainEventBus();
        bus.bindMainThread(Thread.currentThread());
        ChainWatchdog watchdog = new ChainWatchdog(bus);
        return new Harness(bus, watchdog);
    }

    private static void drive(Harness h, ChainPhaseChanged e) {
        h.bus.publish(e);
        h.bus.drain();
    }

    /** 推进信号（PlanProgress/ExecutionAdvanced）经 bus publish + drain 触发 onProgress 订阅者。 */
    private static void driveProgress(Harness h, ChainEvent event) {
        h.bus.publish(event);
        h.bus.drain();
    }

    private static void tick(Harness h, long tick) {
        TickEvent.ServerTickEvent event = new TickEvent.ServerTickEvent(TickEvent.Phase.START);
        // 通过反射不可行（final MCS），改直接调用 onServerTick（package-private 不可见，但同包可访问）
        // 本测试与 ChainWatchdog 同包，onServerTick 是 public @SubscribeEvent 方法，可直接调
        // 但 tick 来源 ChainTickSource.currentServerTick() 依赖 Forge 运行时返回 -1，
        // 故测试必须通过 Config 注入 + 直接 onServerTick，但 tick 值来自 ChainTickSource（无 Forge 返回 -1）。
        // 解决：测试改为反射绕过 ChainTickSource，或测试驱动逻辑用同包包级可见方法。
        // 实际：onServerTick 内 ChainTickSource.currentServerTick() 返回 -1 → 直接 return（无 Forge）。
        // 故本测试覆盖"无 Forge 运行时不误触发"分支，其余通过 onPhaseChanged 镜像断言。
        h.watchdog.onServerTick(event);
    }

    private static ChainPhaseChanged phase(UUID uuid, int gen, int from, int to, long tick) {
        return new ChainPhaseChanged(uuid, gen,
                club.heiqi.qz_miner.chain.statemachine.ChainPhase.values()[from],
                club.heiqi.qz_miner.chain.statemachine.ChainPhase.values()[to],
                tick, 0L);
    }

    /** 构造带服务端轮次关联的进态广播。 */
    private static ChainPhaseChanged phase(UUID uuid, long roundId, int gen, int from, int to, long tick) {
        return new ChainPhaseChanged(uuid, roundId, gen,
                club.heiqi.qz_miner.chain.statemachine.ChainPhase.values()[from],
                club.heiqi.qz_miner.chain.statemachine.ChainPhase.values()[to],
                tick, 0L);
    }

    private static final class Harness {
        final ChainEventBus bus;
        final ChainWatchdog watchdog;

        Harness(ChainEventBus bus, ChainWatchdog watchdog) {
            this.bus = bus;
            this.watchdog = watchdog;
        }
    }

    /** F.3 A-armed-skip：ARMED 不计时（to=ARMED 不新增镜像条目）。 */
    @Test
    public void armedDoesNotStartTracking() {
        Harness h = newHarness();
        // IDLE(0) → ARMED(1)
        drive(h, phase(PLAYER, 0, 0, 1, 10L));
        Assert.assertEquals("ARMED 不应启动计时", 0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));
    }

    /** PLANNING 启动计时（镜像新增）。 */
    @Test
    public void planningStartsTracking() {
        Harness h = newHarness();
        // ARMED(0) → PLANNING(1)
        drive(h, phase(PLAYER, 1, 1, 2, 10L));
        Assert.assertEquals("PLANNING 应启动计时", 1, h.watchdog.activeCount());
        ChainWatchdog.WatchEntry entry = h.watchdog.getEntry(PLAYER);
        Assert.assertNotNull(entry);
        Assert.assertEquals(1, entry.generation);
        Assert.assertEquals(10L, entry.lastProgressTick);
    }

    /** 回 IDLE 停止追踪（镜像移除）。 */
    @Test
    public void idleRemovesTracking() {
        Harness h = newHarness();
        drive(h, phase(PLAYER, 1, 1, 2, 10L));
        Assert.assertEquals(1, h.watchdog.activeCount());
        // PLANNING → IDLE
        drive(h, phase(PLAYER, 1, 2, 0, 20L));
        Assert.assertEquals("回 IDLE 应移除镜像", 0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));
    }

    /** gen 竞态：新一代 ChainPhaseChanged 覆盖旧 gen（镜像刷新）。 */
    @Test
    public void newGenerationOverridesOld() {
        Harness h = newHarness();
        // gen=1 进 PLANNING
        drive(h, phase(PLAYER, 1, 1, 2, 10L));
        ChainWatchdog.WatchEntry e1 = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals(1, e1.generation);
        // gen=2 再进 PLANNING（新一代覆盖）
        drive(h, phase(PLAYER, 2, 1, 2, 15L));
        ChainWatchdog.WatchEntry e2 = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals("新一代应覆盖旧 gen", 2, e2.generation);
        Assert.assertEquals(15L, e2.lastProgressTick);
    }

    /** currentTick &lt; 0 跳过（无 Forge 运行时不误触发，不 publish WatchdogTimeout）。 */
    @Test
    public void noForgeRuntimeDoesNotFire() {
        Harness h = newHarness();
        // PLANNING 启动计时（tick 字段是 ChainPhaseChanged.serverTick，仅诊断，不影响 currentTick 判定）
        drive(h, phase(PLAYER, 1, 1, 2, 10L));
        Assert.assertEquals(1, h.watchdog.activeCount());

        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // 无 Forge 运行时：ChainTickSource.currentServerTick() 返回 -1 → onServerTick 直接 return
        tick(h, 1000L); // 参数 tick 不被使用（onServerTick 自取 ChainTickSource）

        Assert.assertTrue("无 Forge 运行时不应 publish WatchdogTimeout", captured.isEmpty());
        Assert.assertEquals("镜像条目不应被移除", 1, h.watchdog.activeCount());
    }

    /** Config 阈值默认值断言（防回归）。 */
    @Test
    public void defaultThresholdIs50() {
        Assert.assertEquals("默认看门狗阈值应为 50 tick（B 方案落地后收紧）", 50, Config.chainWatchdogTimeoutTicks);
    }

    /** PLANNING/RUNNING/FINISHING 三态均启动计时（to != IDLE && to != ARMED）。 */
    @Test
    public void allActivePhasesTracked() {
        Harness h = newHarness();
        // ARMED → PLANNING
        drive(h, phase(PLAYER, 1, 1, 2, 10L));
        Assert.assertEquals(1, h.watchdog.activeCount());
        // PLANNING → RUNNING
        drive(h, phase(PLAYER, 1, 2, 3, 11L));
        Assert.assertEquals("RUNNING 应保持追踪", 1, h.watchdog.activeCount());
        // RUNNING → FINISHING
        drive(h, phase(PLAYER, 1, 3, 4, 12L));
        Assert.assertEquals("FINISHING 应保持追踪", 1, h.watchdog.activeCount());
        // FINISHING → IDLE
        drive(h, phase(PLAYER, 1, 4, 0, 13L));
        Assert.assertEquals("回 IDLE 应移除追踪", 0, h.watchdog.activeCount());
    }

    // ============================ P1-1 核心超时路径（checkTimeouts 注入 tick） ============================

    /**
     * P1-1 核心场景：N tick 无推进触发 WatchdogTimeout 且镜像立即移除（F.4 C1）。
     *
     * <p>提取 {@link ChainWatchdog#checkTimeouts} 包级方法后，单测可注入 forcedTick 绕过
     * {@code ChainTickSource.currentServerTick()}（纯 JVM 返回 -1 的死路），直接驱动超时判定分支。</p>
     */
    @Test
    public void timeoutPublishesWatchdogTimeoutAndRemovesEntry() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        // gen=1 进 RUNNING，serverTick=100 刷 lastProgressTick
        drive(h, phase(PLAYER, 1, 1, 3, 100L));
        Assert.assertEquals(1, h.watchdog.activeCount());

        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // 推进超过阈值：100 + threshold + 1
        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();

        Assert.assertEquals("超时应 publish 一条 WatchdogTimeout", 1, captured.size());
        WatchdogTimeout wt = captured.get(0);
        Assert.assertEquals(PLAYER, wt.getPlayerUUID());
        Assert.assertEquals("gen 应来自镜像条目", 1, wt.getGeneration());
        Assert.assertEquals("F.4 C1：publish 后应立即移除镜像条目（防风暴）", 0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));
    }

    /**
     * P1-1 推进刷新：ChainPhaseChanged 刷新 lastProgressTick，未超时不触发。
     *
     * <p>验证推进信号（进态广播）正确刷新 {@link ChainWatchdog.WatchEntry#lastProgressTick}，
     * 正常推进的连锁不会误触发看门狗。</p>
     */
    @Test
    public void progressRefreshPreventsTimeout() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;

        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // gen=1 进 RUNNING，serverTick=100
        drive(h, phase(PLAYER, 1, 1, 3, 100L));
        // 推进 threshold/2 tick，未超时
        h.watchdog.checkTimeouts(100L + threshold / 2);
        h.bus.drain();
        Assert.assertTrue("未超时不应 publish", captured.isEmpty());

        // 再喂 ChainPhaseChanged 刷新 lastProgressTick=100+threshold/2
        drive(h, phase(PLAYER, 1, 3, 3, 100L + threshold / 2));
        ChainWatchdog.WatchEntry entry = h.watchdog.getEntry(PLAYER);
        Assert.assertNotNull(entry);
        Assert.assertEquals("推进应刷新 lastProgressTick", 100L + threshold / 2, entry.lastProgressTick);

        // 从新 lastProgressTick 算，推进到 100+threshold（elapsed = threshold/2 < threshold）未超时
        h.watchdog.checkTimeouts(100L + threshold);
        h.bus.drain();
        Assert.assertTrue("推进刷新后未超时不应 publish", captured.isEmpty());
        Assert.assertEquals("未超时镜像条目不应被移除", 1, h.watchdog.activeCount());
    }

    /** deadline scheduler deferral 在下一 Tick drain 后续命，不能被当成执行卡死。 */
    @Test
    public void deadlineDeferralFeedsWatchdogBeforeTimeoutCheck() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        long roundId = 42L;
        drive(h, phase(PLAYER, roundId, 1, 1, 3, 100L));

        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);
        driveProgress(h, new ExecutionDeferred(PLAYER, roundId, 1,
                100L + threshold - 1L, 1L));

        h.watchdog.checkTimeouts(100L + threshold);
        h.bus.drain();

        Assert.assertTrue("仅因 shared deadline 排队的 context 不应被误杀", captured.isEmpty());
        ChainWatchdog.WatchEntry entry = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals("deadline deferral 不得伪装成真实推进", 100L, entry.lastProgressTick);
        Assert.assertEquals(100L + threshold - 1L, entry.firstDeferredTick);
        Assert.assertEquals(100L + threshold - 1L, entry.lastDeferredTick);
    }

    /** 连续零消费不能靠每 tick deadline deferral 永久逃过 watchdog。 */
    @Test
    public void continuousDeadlineDeferralHasOneBoundedWatchdogWindow() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        long roundId = 43L;
        long firstDeferredTick = 100L + threshold - 1L;
        drive(h, phase(PLAYER, roundId, 1, 1, 3, 100L));

        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);
        for (long tick = firstDeferredTick; tick < firstDeferredTick + threshold; tick++) {
            driveProgress(h, new ExecutionDeferred(PLAYER, roundId, 1, tick, tick));
        }
        h.watchdog.checkTimeouts(firstDeferredTick + threshold - 1L);
        h.bus.drain();
        Assert.assertTrue("一个完整宽限窗口内不应误杀排队 context", captured.isEmpty());

        driveProgress(h, new ExecutionDeferred(PLAYER, roundId, 1,
                firstDeferredTick + threshold, firstDeferredTick + threshold));
        h.watchdog.checkTimeouts(firstDeferredTick + threshold);
        h.bus.drain();
        Assert.assertTrue("drain 延迟边界仍应保留最后一 Tick 宽限", captured.isEmpty());

        driveProgress(h, new ExecutionDeferred(PLAYER, roundId, 1,
                firstDeferredTick + threshold + 1L, firstDeferredTick + threshold + 1L));
        h.watchdog.checkTimeouts(firstDeferredTick + threshold + 1L);
        h.bus.drain();

        Assert.assertEquals("连续零消费超过宽限窗口必须回收", 1, captured.size());
        Assert.assertEquals(0, h.watchdog.activeCount());
    }

    /**
     * P1-1 防风暴：超时 publish 后镜像立即移除，后续 tick 不重复 publish（F.4 C1）。
     *
     * <p>验证 F.4 C1 裁决落地：publish WatchdogTimeout 后立即从镜像移除该条目，
     * 避免下一 tick 重复 publish 致看门狗风暴。</p>
     */
    @Test
    public void noRepeatedPublishAfterRemoval() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;

        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // gen=1 进 RUNNING，serverTick=100
        drive(h, phase(PLAYER, 1, 1, 3, 100L));

        // 第一次超时：100 + threshold + 1
        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();
        Assert.assertEquals("第一次超时应 publish 一条", 1, captured.size());

        // 后续 tick：镜像已移除，不应重复 publish
        h.watchdog.checkTimeouts(100L + threshold + 2);
        h.bus.drain();
        Assert.assertEquals("F.4 C1：镜像移除后不应重复 publish（防风暴）", 1, captured.size());
        Assert.assertEquals("镜像应保持空", 0, h.watchdog.activeCount());
    }

    /**
     * P2-2：超时 publish 的 elapsedNanos 是真实 delta（nowNanos - lastNanos），非占位绝对值。
     *
     * <p>阶段8 块3 收口：原占位 {@code Math.max(0, nanos)} 是 System.nanoTime() 绝对值（几十亿纳秒级），
     * P2-2 改为真 delta（进态到超时的时间差，毫秒级以内）。本测断言 elapsedNanos 落在合理小区间
     * （&lt; 1 秒 = 1e9 纳秒），区分占位与真值。</p>
     */
    @Test
    public void elapsedNanosIsRealDeltaNotPlaceholder() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;

        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // gen=1 进 RUNNING，记录 lastNanos
        drive(h, phase(PLAYER, 1, 1, 3, 100L));

        // 触发超时
        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();

        Assert.assertEquals("应 publish 一条 WatchdogTimeout", 1, captured.size());
        long elapsedNanos = captured.get(0).getElapsedNanos();
        // 真 delta 是进态到超时的真实时间差（单测内应远小于 1 秒）；
        // 旧占位是 System.nanoTime() 绝对值（远大于 1e9），本断言可区分
        Assert.assertTrue("P2-2：elapsedNanos 应是真实 delta（< 1e9 ns），实际=" + elapsedNanos,
                elapsedNanos >= 0L && elapsedNanos < 1_000_000_000L);
    }

    // ============================ B 方案：PlanProgress / ExecutionAdvanced 推进信号 ============================

    /**
     * B 方案核心场景1：PLANNING 阶段持续 publish PlanProgress 喂狗，threshold 后不触发 WatchdogTimeout。
     *
     * <p>复现实机 62.5% PLANNING 超时误杀根因：PLANNING 两次状态机转移之间无 ChainPhaseChanged，
     * 长规划被误判卡死。补订阅 PlanProgress 后，worker 分片推进信号刷新 lastProgressTick，正常规划不误杀。</p>
     */
    @Test
    public void planProgressFeedsWatchdogDuringPlanning() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // gen=1 进 PLANNING，serverTick=100
        drive(h, phase(PLAYER, 1, 1, 2, 100L));
        Assert.assertEquals(1, h.watchdog.activeCount());

        // 持续 publish PlanProgress（gen 匹配），模拟 worker 每分片推进
        for (long t = 100L; t < 100L + threshold + 5; t += 5L) {
            driveProgress(h, new PlanProgress(PLAYER, 1, t, t * 1_000_000L, 0, 0));
        }
        // 此时 lastProgressTick 应被刷新到最后一次 PlanProgress 的 serverTick（=100+threshold）
        ChainWatchdog.WatchEntry entry = h.watchdog.getEntry(PLAYER);
        Assert.assertNotNull(entry);
        Assert.assertEquals("PlanProgress 应刷新 lastProgressTick", 100L + threshold, entry.lastProgressTick);

        // checkTimeouts 在 threshold 后不应触发（因为持续喂狗刷新了 lastProgressTick）
        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();
        Assert.assertTrue("PLANNING 持续 PlanProgress 喂狗不应触发 WatchdogTimeout", captured.isEmpty());
        Assert.assertEquals("镜像条目不应被移除", 1, h.watchdog.activeCount());
    }

    /**
     * B 方案核心场景2：陈旧 gen 的 PlanProgress 不刷新新代际条目（世代隔离防护）。
     *
     * <p>gen=2 的条目收到 gen=1 的迟到 PlanProgress 不应被刷新，否则会给已回 IDLE 后的新代际「续命」
     * 掩盖真卡死。本测断言陈旧 gen 事件被 onProgress 直接 return，条目保持原 lastProgressTick，
     * threshold 后仍触发超时。</p>
     */
    @Test
    public void staleGenPlanProgressDoesNotRefresh() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // gen=2 进 PLANNING，serverTick=100
        drive(h, phase(PLAYER, 2, 1, 2, 100L));
        ChainWatchdog.WatchEntry entryBefore = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals(2, entryBefore.generation);
        Assert.assertEquals(100L, entryBefore.lastProgressTick);

        // publish 陈旧 gen=1 的 PlanProgress（gen 不匹配）
        driveProgress(h, new PlanProgress(PLAYER, 1, 100L + threshold, (100L + threshold) * 1_000_000L, 0, 0));

        // 条目应保持原 lastProgressTick=100，未被陈旧 gen 刷新
        ChainWatchdog.WatchEntry entryAfter = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals("陈旧 gen 的 PlanProgress 不应刷新 lastProgressTick",
                100L, entryAfter.lastProgressTick);
        Assert.assertEquals("generation 应保持 2", 2, entryAfter.generation);

        // threshold 后应触发超时（说明陈旧 gen 没误刷新）
        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();
        Assert.assertEquals("陈旧 gen 未刷新，应触发 WatchdogTimeout", 1, captured.size());
        Assert.assertEquals("超时事件 gen 应来自镜像条目（=2）", 2, captured.get(0).getGeneration());
    }

    /**
     * B 方案核心场景3：RUNNING 阶段持续 publish ExecutionAdvanced 喂狗，threshold 后不触发。
     *
     * <p>与 PLANNING 同构：RUNNING 两次状态机转移之间靠 ExecutionAdvanced（每 tick 破坏后 publish）
     * 刷新 lastProgressTick，长执行不误杀。</p>
     */
    @Test
    public void executionAdvancedFeedsWatchdogDuringRunning() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // gen=1 进 RUNNING，serverTick=200
        drive(h, phase(PLAYER, 1, 2, 3, 200L));
        Assert.assertEquals(1, h.watchdog.activeCount());

        // 持续 publish ExecutionAdvanced（gen 匹配），模拟每 tick 破坏推进
        for (long t = 200L; t < 200L + threshold + 5; t += 3L) {
            driveProgress(h, new ExecutionAdvanced(PLAYER, 1, t, t * 1_000_000L, 1, 10));
        }
        ChainWatchdog.WatchEntry entry = h.watchdog.getEntry(PLAYER);
        Assert.assertNotNull(entry);
        // 最后一次 t = 200 + (threshold+3) 左右的 3 倍数；断言已被刷新远超初始 200
        Assert.assertTrue("ExecutionAdvanced 应刷新 lastProgressTick，实际=" + entry.lastProgressTick,
                entry.lastProgressTick > 200L + threshold / 2);

        // threshold 后不应触发（持续喂狗）
        h.watchdog.checkTimeouts(entry.lastProgressTick + 1);
        h.bus.drain();
        Assert.assertTrue("RUNNING 持续 ExecutionAdvanced 喂狗不应触发 WatchdogTimeout", captured.isEmpty());
        Assert.assertEquals("镜像条目不应被移除", 1, h.watchdog.activeCount());
    }

    /** 消费但零成功同样是真实推进，executedThisTick=0 必须刷新 RUNNING 看门狗。 */
    @Test
    public void zeroSuccessExecutionAdvancedStillFeedsWatchdog() {
        Harness h = newHarness();
        drive(h, phase(PLAYER, 1, 2, 3, 200L));

        driveProgress(h, new ExecutionAdvanced(PLAYER, 1, 225L, 225_000_000L, 0, 4));

        ChainWatchdog.WatchEntry entry = h.watchdog.getEntry(PLAYER);
        Assert.assertNotNull(entry);
        Assert.assertEquals("目标跳过/执行失败的消费推进也必须喂狗", 225L, entry.lastProgressTick);
    }

    // ============================ P2-1 漏路径补强（B 方案边界不变量） ============================

    /**
     * 漏路径1：onProgress 在 existing==null 时 return 不新建条目。
     *
     * <p>场景：玩家无活跃条目（IDLE/ARMED 期）时收到迟到的 PlanProgress/ExecutionAdvanced。
     * 守信号源分工：进态广播建条目，工作推进信号只续命不新建。本测断言 onProgress 不误建条目，
     * 且后续 checkTimeouts 也不误触发。</p>
     */
    @Test
    public void progressEventWithNoActiveEntryDoesNotCreateEntry() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // 玩家全程未进 PLANNING/RUNNING/FINISHING（无 ChainPhaseChanged 驱动建条目）
        Assert.assertEquals(0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));

        // 迟到的 PlanProgress（existing==null）
        driveProgress(h, new PlanProgress(PLAYER, 1, 100L, 100L * 1_000_000L, 0, 0));
        Assert.assertEquals("onProgress 不应为无条目玩家误建条目", 0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));

        // 迟到的 ExecutionAdvanced（existing==null）
        driveProgress(h, new ExecutionAdvanced(PLAYER, 1, 100L, 100L * 1_000_000L, 1, 10));
        Assert.assertEquals("onProgress 不应为无条目玩家误建条目", 0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));

        // 之后 checkTimeouts 也不误触发（镜像为空）
        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();
        Assert.assertTrue("无条目不应 publish WatchdogTimeout", captured.isEmpty());
    }

    /**
     * 漏路径2：onPhaseChanged to==ARMED 期间收到推进信号不误建/误刷。
     *
     * <p>场景：玩家进 ARMED（onPhaseChanged to==ARMED 应 return 不新增条目，F.3 A-armed-skip），
     * 此时收到 PlanProgress。期望 onProgress 因 existing==null return，activePlayers 仍无条目。
     * 本测固化「ARMED 期间 activePlayers 不应有残留」不变量。</p>
     */
    @Test
    public void armedPhaseDoesNotAcceptProgressSignal() {
        Harness h = newHarness();
        // IDLE(0) → ARMED(1)：F.3 A-armed-skip 不新增条目
        drive(h, phase(PLAYER, 0, 0, 1, 10L));
        Assert.assertEquals("ARMED 不应启动计时", 0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));

        // ARMED 期间收到 PlanProgress（gen=0 与 ARMED 进态 gen 一致也无条目可刷）
        driveProgress(h, new PlanProgress(PLAYER, 0, 20L, 20L * 1_000_000L, 0, 0));
        Assert.assertEquals("ARMED 期 onProgress 不应误建条目", 0, h.watchdog.activeCount());
        Assert.assertNull("ARMED 期 activePlayers 不应有该玩家残留", h.watchdog.getEntry(PLAYER));

        // 同样收 ExecutionAdvanced 也不误建
        driveProgress(h, new ExecutionAdvanced(PLAYER, 0, 25L, 25L * 1_000_000L, 1, 10));
        Assert.assertEquals("ARMED 期 onProgress 不应误建条目", 0, h.watchdog.activeCount());
        Assert.assertNull(h.watchdog.getEntry(PLAYER));
    }

    /**
     * 漏路径3：双向 gen 隔离的反向——新 gen 事件不刷新旧 gen 条目。
     *
     * <p>已有 {@link #staleGenPlanProgressDoesNotRefresh} 测的是 gen=2 条目收 gen=1 事件（旧 gen 不刷新新 gen 条目）。
     * 本测补反向：gen=1 条目收 gen=2 事件。期望 onProgress 因 {@code existing.generation(1) != eventGen(2)}
     * return，gen=1 条目保持原 lastProgressTick，threshold 后仍按 gen=1 触发超时。</p>
     */
    @Test
    public void newerGenPlanProgressDoesNotRefreshOlderGenEntry() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        // gen=1 进 PLANNING，serverTick=100
        drive(h, phase(PLAYER, 1, 1, 2, 100L));
        ChainWatchdog.WatchEntry entryBefore = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals(1, entryBefore.generation);
        Assert.assertEquals(100L, entryBefore.lastProgressTick);

        // publish 新 gen=2 的 PlanProgress（gen 不匹配，反向隔离）
        driveProgress(h, new PlanProgress(PLAYER, 2, 100L + threshold, (100L + threshold) * 1_000_000L, 0, 0));

        // 条目应保持原 gen=1、lastProgressTick=100，未被新 gen 刷新
        ChainWatchdog.WatchEntry entryAfter = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals("新 gen 的 PlanProgress 不应刷新旧 gen 条目",
                100L, entryAfter.lastProgressTick);
        Assert.assertEquals("generation 应保持 1（新 gen 事件不接管旧条目）", 1, entryAfter.generation);

        // threshold 后应按 gen=1 触发超时（说明新 gen 没误刷新）
        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();
        Assert.assertEquals("新 gen 未刷新旧 gen，应按 gen=1 触发 WatchdogTimeout", 1, captured.size());
        Assert.assertEquals("超时事件 gen 应来自镜像条目（=1）", 1, captured.get(0).getGeneration());
    }

    /** 同 generation 的旧轮进度不得给新轮 entry 续命，timeout 必须携带新轮 round。 */
    @Test
    public void oldRoundProgressDoesNotRefreshSameGenerationNewRound() {
        Harness h = newHarness();
        int threshold = Config.chainWatchdogTimeoutTicks;
        List<WatchdogTimeout> captured = new ArrayList<WatchdogTimeout>();
        h.bus.subscribe(WatchdogTimeout.class, captured::add);

        drive(h, phase(PLAYER, 602L, 8, 1, 2, 100L));
        driveProgress(h, new PlanProgress(PLAYER, 601L, 8, 100L + threshold, 0L, 1, 1));

        ChainWatchdog.WatchEntry entry = h.watchdog.getEntry(PLAYER);
        Assert.assertEquals("旧轮进度不得续命新轮", 100L, entry.lastProgressTick);
        Assert.assertEquals(602L, entry.serverRoundId);

        h.watchdog.checkTimeouts(100L + threshold + 1);
        h.bus.drain();
        Assert.assertEquals(1, captured.size());
        Assert.assertEquals("旧 timeout 不得错标为 R1", 602L, captured.get(0).getServerRoundId());
    }
}
