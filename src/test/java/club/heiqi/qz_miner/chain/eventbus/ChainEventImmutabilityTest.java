package club.heiqi.qz_miner.chain.eventbus;

import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionAdvanced;
import club.heiqi.qz_miner.chain.eventbus.event.ExecutionFinished;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.ModeSwitched;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.chain.eventbus.event.PlanProgress;
import club.heiqi.qz_miner.chain.eventbus.event.PlanStarted;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

import java.lang.reflect.Modifier;

/**
 * {@link ChainEvent} 不可变性反射测试。
 */
public class ChainEventImmutabilityTest {

    private static final Class<?>[] EVENT_CLASSES = {
            ChainEvent.class,
            ChainKeyPressed.class,
            BlockBreakObserved.class,
            ModeSwitched.class,
            PlanStarted.class,
            PlanProgress.class,
            PlanCompleted.class,
            PlanCancelled.class,
            ExecutionAdvanced.class,
            ExecutionFinished.class,
            LifecycleCleanup.class,
            WatchdogTimeout.class,
            ChainPhaseChanged.class,
    };

    /**
     * 反射遍历 ChainEvent 及 11 子类所有声明字段，断言每个字段为 final。
     */
    @Test
    public void allEventFieldsAreFinal() {
        for (Class<?> c : EVENT_CLASSES) {
            for (Field f : c.getDeclaredFields()) {
                Assert.assertTrue(
                        "field " + c.getSimpleName() + "." + f.getName() + " must be final",
                        Modifier.isFinal(f.getModifiers()));
            }
        }
    }

    /**
     * 构造 ChainKeyPressed，断言父类 4 字段 getter 返回构造入参。
     */
    @Test
    public void baseFieldsPreservedThroughSubclass() {
        UUID player = UUID.randomUUID();
        int generation = 7;
        long tick = 42L;
        long nanos = 123456789L;
        ChainKeyPressed e = new ChainKeyPressed(player, generation, tick, nanos, true);
        Assert.assertEquals(generation, e.getGeneration());
        Assert.assertEquals(tick, e.getServerTick());
        Assert.assertEquals(player, e.getPlayerUUID());
        Assert.assertEquals(nanos, e.getTimestampNanos());

        // 顺带验证一个非平凡子类（带自有字段）也保留父类字段
        ModeSwitched m = new ModeSwitched(player, generation, tick, nanos, ChainMode.CHAIN, ChainSubMode.CHAIN_BASE);
        Assert.assertEquals(generation, m.getGeneration());
        Assert.assertEquals(tick, m.getServerTick());
        Assert.assertEquals(player, m.getPlayerUUID());
        Assert.assertEquals(nanos, m.getTimestampNanos());
    }

    /**
     * 阶段7 LifecycleCleanup 扩字段（forced + removeSlot）保留断言。
     *
     * <p>全参构造器写入的 forced/removeSlot 经 getter 原样返回；
     * 兼容 5 参构造器默认 forced=false/removeSlot=false。</p>
     */
    @Test
    public void lifecycleCleanupForcedAndRemoveSlotPreserved() {
        UUID player = UUID.randomUUID();
        // 全参：forced=true + removeSlot=true
        LifecycleCleanup full = new LifecycleCleanup(player, 3, 42L, 999L, "test", true, true);
        Assert.assertTrue("forced 应原样返回", full.isForced());
        Assert.assertTrue("removeSlot 应原样返回", full.isRemoveSlot());
        Assert.assertEquals("test", full.getReason());

        // 兼容 5 参构造器：默认 forced=false + removeSlot=false
        LifecycleCleanup compat = new LifecycleCleanup(player, 3, 42L, 999L, "compat");
        Assert.assertFalse("兼容构造器 forced 默认 false", compat.isForced());
        Assert.assertFalse("兼容构造器 removeSlot 默认 false", compat.isRemoveSlot());
    }
}
