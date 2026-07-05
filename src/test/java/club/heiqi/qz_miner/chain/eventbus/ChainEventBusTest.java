package club.heiqi.qz_miner.chain.eventbus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.event.BlockBreakObserved;
import club.heiqi.qz_miner.chain.eventbus.event.ChainKeyPressed;

/**
 * {@link ChainEventBus} 行为测试（JUnit 4）。
 *
 * <p>不依赖 FML 运行时；但需 MyMod.LOG 静态字段在类加载时初始化（log4j 可在 JVM 环境加载）。
 * 若 MyMod 静态初始化因 FML 缺失失败，将 {@link ChainEventBus} 的 drain warn 日志改注入可绕过，
 * 当前框架下 MyMod.LOG = LogManager.getLogger(...) 仅依赖 log4j，可在纯 JVM 加载。</p>
 */
public class ChainEventBusTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private ChainKeyPressed keyEvent() {
        return new ChainKeyPressed(PLAYER, 1, 0L, System.nanoTime(), true);
    }

    private BlockBreakObserved breakEvent() {
        return new BlockBreakObserved(PLAYER, 1, 0L, System.nanoTime(), 0, 0, 0, 0, 0);
    }

    /**
     * 主线程 publish 3 事件 → drain → 订阅者按 FIFO 收到。
     */
    @Test
    public void publishThenDrainDeliversInOrder() {
        ChainEventBus bus = new ChainEventBus();
        final List<Integer> seen = Collections.synchronizedList(new ArrayList<Integer>());
        bus.subscribe(ChainKeyPressed.class, e -> seen.add(e.getGeneration()));
        bus.publish(new ChainKeyPressed(PLAYER, 1, 0L, 0L, true));
        bus.publish(new ChainKeyPressed(PLAYER, 2, 0L, 0L, true));
        bus.publish(new ChainKeyPressed(PLAYER, 3, 0L, 0L, true));
        int n = bus.drain();
        Assert.assertEquals(3, n);
        Assert.assertEquals(3, seen.size());
        Assert.assertEquals(Integer.valueOf(1), seen.get(0));
        Assert.assertEquals(Integer.valueOf(2), seen.get(1));
        Assert.assertEquals(Integer.valueOf(3), seen.get(2));
    }

    /**
     * 订阅 ChainKeyPressed，publish ChainKeyPressed + BlockBreakObserved，只收到前者。
     */
    @Test
    public void subscriberReceivesOnlyMatchingType() {
        ChainEventBus bus = new ChainEventBus();
        final AtomicInteger keyCount = new AtomicInteger();
        bus.subscribe(ChainKeyPressed.class, e -> keyCount.incrementAndGet());
        bus.publish(keyEvent());
        bus.publish(breakEvent());
        bus.drain();
        Assert.assertEquals(1, keyCount.get());
    }

    /**
     * 起多线程各 publish 若干事件，join 后 drain，断言总条数守恒。
     */
    @Test
    public void crossThreadPublishMainThreadDrainOrdered() throws InterruptedException {
        final ChainEventBus bus = new ChainEventBus();
        final int threads = 8;
        final int perThread = 50;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        bus.publish(keyEvent());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            th.start();
        }
        start.countDown();
        done.await();
        int n = bus.drain();
        Assert.assertEquals(threads * perThread, n);
    }

    /**
     * drain 返回值 = 处理条数；空队列 drain 返回 0。
     */
    @Test
    public void drainReturnsProcessedCount() {
        ChainEventBus bus = new ChainEventBus();
        Assert.assertEquals(0, bus.drain());
        bus.publish(keyEvent());
        bus.publish(keyEvent());
        Assert.assertEquals(2, bus.drain());
        Assert.assertEquals(0, bus.drain());
    }

    /**
     * 一个订阅者抛 RuntimeException，后续事件仍被处理（验证 catch 隔离）。
     */
    @Test
    public void subscriberExceptionDoesNotBreakDrain() {
        ChainEventBus bus = new ChainEventBus();
        final AtomicInteger after = new AtomicInteger();
        bus.subscribe(ChainKeyPressed.class, e -> {
            throw new RuntimeException("boom");
        });
        bus.subscribe(ChainKeyPressed.class, e -> after.incrementAndGet());
        bus.publish(keyEvent());
        bus.publish(keyEvent());
        bus.drain();
        // 第二个订阅者在第一个抛异常后仍被调用，2 事件 × 1 正常订阅者 = 2
        Assert.assertEquals(2, after.get());
    }

    /**
     * 同一事件类型注册 2 订阅者，drain 后两者都被调用。
     */
    @Test
    public void multipleSubscribersAllInvoked() {
        ChainEventBus bus = new ChainEventBus();
        final AtomicInteger a = new AtomicInteger();
        final AtomicInteger b = new AtomicInteger();
        bus.subscribe(ChainKeyPressed.class, e -> a.incrementAndGet());
        bus.subscribe(ChainKeyPressed.class, e -> b.incrementAndGet());
        bus.publish(keyEvent());
        bus.drain();
        Assert.assertEquals(1, a.get());
        Assert.assertEquals(1, b.get());
    }

    /**
     * publish 后 clear() → drain 返回 0；再 publish 同类型仍被已注册订阅者收到。
     */
    @Test
    public void clearDropsPendingButKeepsSubscribers() {
        ChainEventBus bus = new ChainEventBus();
        final AtomicInteger count = new AtomicInteger();
        bus.subscribe(ChainKeyPressed.class, e -> count.incrementAndGet());
        bus.publish(keyEvent());
        bus.publish(keyEvent());
        bus.clear();
        Assert.assertEquals(0, bus.drain());
        Assert.assertEquals(0, count.get());
        bus.publish(keyEvent());
        bus.drain();
        Assert.assertEquals(1, count.get());
    }
}
