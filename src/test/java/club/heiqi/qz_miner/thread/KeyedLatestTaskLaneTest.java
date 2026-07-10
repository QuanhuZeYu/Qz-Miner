package club.heiqi.qz_miner.thread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

/** Keyed latest-wins 泳道的纯 JVM 确定性测试。 */
public class KeyedLatestTaskLaneTest {

    @Test
    public void sameKeyTenThousandSubmitsKeepSingleSlotWithFinalValue() {
        KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(8, 64);
        lane.start();
        final AtomicInteger last = new AtomicInteger(-1);
        for (int i = 0; i < 10000; i++) {
            final int value = i;
            Assert.assertTrue(lane.submit("endpoint", new Runnable() {
                @Override
                public void run() {
                    last.set(value);
                }
            }));
        }
        Assert.assertEquals(1, lane.pendingCount());
        Assert.assertEquals(1, lane.drain());
        Assert.assertEquals(9999, last.get());
        Assert.assertEquals(0, lane.pendingCount());
    }

    @Test
    public void sixtyFiveKeysFirstDrainSixtyFourThenOne() {
        KeyedLatestTaskLane<Integer> lane = new KeyedLatestTaskLane<Integer>(256, 64);
        lane.start();
        final List<Integer> drained = Collections.synchronizedList(new ArrayList<Integer>());
        for (int i = 0; i < 65; i++) {
            final int key = i;
            Assert.assertTrue(lane.submit(Integer.valueOf(key), new Runnable() {
                @Override
                public void run() {
                    drained.add(Integer.valueOf(key));
                }
            }));
        }
        Assert.assertEquals(65, lane.pendingCount());
        Assert.assertEquals(64, lane.drain());
        Assert.assertEquals(1, lane.pendingCount());
        Assert.assertEquals(1, lane.drain());
        Assert.assertEquals(0, lane.pendingCount());
        Assert.assertEquals(65, drained.size());
    }

    @Test
    public void concurrentUpdateDuringDrainEndsWithFinalValue() throws Exception {
        KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(8, 64);
        lane.start();
        final AtomicInteger observed = new AtomicInteger(-1);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Assert.assertTrue(lane.submit("k", new Runnable() {
            @Override
            public void run() {
                entered.countDown();
                await(release);
                observed.set(1);
            }
        }));
        Thread drainer = new Thread(new Runnable() {
            @Override
            public void run() {
                lane.drain();
            }
        }, "lane-drain");
        drainer.start();
        Assert.assertTrue(entered.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(lane.submit("k", new Runnable() {
            @Override
            public void run() {
                observed.set(2);
            }
        }));
        release.countDown();
        drainer.join(5000L);
        Assert.assertFalse(drainer.isAlive());
        // 并发更新在 drain 移除后再次入槽；再 drain 一次拿到最终值
        if (lane.pendingCount() > 0) {
            Assert.assertEquals(1, lane.drain());
        }
        Assert.assertEquals(2, observed.get());
    }

    @Test
    public void capacityFullRejectsNewKeyButAllowsExistingKeyUpdate() {
        KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(2, 64);
        lane.start();
        final AtomicInteger a = new AtomicInteger();
        final AtomicInteger b = new AtomicInteger();
        final AtomicInteger c = new AtomicInteger();
        Assert.assertTrue(lane.submit("a", set(a, 1)));
        Assert.assertTrue(lane.submit("b", set(b, 1)));
        Assert.assertFalse(lane.submit("c", set(c, 1)));
        Assert.assertTrue(lane.submit("a", set(a, 9)));
        Assert.assertEquals(2, lane.pendingCount());
        lane.drain();
        Assert.assertEquals(9, a.get());
        Assert.assertEquals(1, b.get());
        Assert.assertEquals(0, c.get());
    }

    @Test
    public void stopAndRestartInvalidatesOldLaneIdentity() {
        KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(8, 64);
        long first = lane.start();
        final AtomicInteger ran = new AtomicInteger();
        Assert.assertTrue(lane.submit("k", set(ran, 1)));
        lane.stop();
        Assert.assertFalse(lane.isOpen());
        Assert.assertFalse(lane.submit("k", set(ran, 2)));
        Assert.assertEquals(0, lane.drain());
        Assert.assertEquals(0, ran.get());

        long second = lane.start();
        Assert.assertTrue(second != first);
        Assert.assertTrue(lane.submit("k", set(ran, 3)));
        Assert.assertEquals(1, lane.drain());
        Assert.assertEquals(3, ran.get());
    }

    @Test
    public void afterProductionStopsAcceptedValueIsEventuallyReachable() throws Exception {
        KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(8, 64);
        lane.start();
        final AtomicReference<Integer> last = new AtomicReference<Integer>();
        final AtomicInteger next = new AtomicInteger();
        final CountDownLatch producersDone = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 250; i++) {
                        final int value = next.incrementAndGet();
                        lane.submit("endpoint", new Runnable() {
                            @Override
                            public void run() {
                                last.set(Integer.valueOf(value));
                            }
                        });
                    }
                    producersDone.countDown();
                }
            }, "producer-" + t).start();
        }
        Assert.assertTrue(producersDone.await(5, TimeUnit.SECONDS));
        int totalDrained = 0;
        while (lane.pendingCount() > 0) {
            totalDrained += lane.drain();
        }
        Assert.assertTrue(totalDrained >= 1);
        Assert.assertNotNull(last.get());
        Assert.assertEquals(0, lane.pendingCount());
    }

    private static Runnable set(final AtomicInteger target, final int value) {
        return new Runnable() {
            @Override
            public void run() {
                target.set(value);
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
