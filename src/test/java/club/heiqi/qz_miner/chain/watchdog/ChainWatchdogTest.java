package club.heiqi.qz_miner.chain.watchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
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
    public void defaultThresholdIs100() {
        Assert.assertEquals("默认看门狗阈值应为 100 tick", 100, Config.chainWatchdogTimeoutTicks);
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
}
