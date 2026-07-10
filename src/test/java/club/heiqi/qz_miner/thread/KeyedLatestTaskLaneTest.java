package club.heiqi.qz_miner.thread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /**
     * 确定性接缝：producer 读到 existing 后暂停 → drain remove+执行 A →
     * producer replace 失败并重建 B → submit 返回 true、pending=1、第二 drain 执行 B。
     */
    @Test
    public void deterministicAcceptedAfterDrainRemoveRebuildsSlot() throws Exception {
        final KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(8, 64);
        lane.start();

        final AtomicInteger observed = new AtomicInteger(0);
        final CountDownLatch taskAEntered = new CountDownLatch(1);
        final CountDownLatch taskARelease = new CountDownLatch(1);

        Assert.assertTrue(lane.submit("k", new Runnable() {
            @Override
            public void run() {
                taskAEntered.countDown();
                await(taskARelease);
                observed.compareAndSet(0, 1);
            }
        }));

        final CountDownLatch observedExisting = new CountDownLatch(1);
        final CountDownLatch resumeProducer = new CountDownLatch(1);
        lane.testBlockAfterObserve = observedExisting;
        lane.testResumeAfterObserve = resumeProducer;

        final AtomicBoolean submitAccepted = new AtomicBoolean(false);
        final AtomicReference<Throwable> producerError = new AtomicReference<Throwable>();
        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean accepted = lane.submit("k", new Runnable() {
                        @Override
                        public void run() {
                            observed.set(2);
                        }
                    });
                    submitAccepted.set(accepted);
                } catch (Throwable t) {
                    producerError.set(t);
                }
            }
        }, "lane-producer-rebuild");
        producer.start();

        Assert.assertTrue("producer should observe existing slot", observedExisting.await(5, TimeUnit.SECONDS));

        Thread drainer = new Thread(new Runnable() {
            @Override
            public void run() {
                lane.drain();
            }
        }, "lane-drain-rebuild");
        drainer.start();
        Assert.assertTrue(taskAEntered.await(5, TimeUnit.SECONDS));
        // A 已 remove 并在执行中；释放 producer 使 replace 失败并重建 B
        resumeProducer.countDown();
        producer.join(5000L);
        Assert.assertFalse(producer.isAlive());
        Assert.assertNull(producerError.get());
        Assert.assertTrue("rebuild submit must return true", submitAccepted.get());
        Assert.assertEquals(1, lane.pendingCount());

        taskARelease.countDown();
        drainer.join(5000L);
        Assert.assertFalse(drainer.isAlive());

        Assert.assertEquals(1, lane.drain());
        Assert.assertEquals(2, observed.get());
        Assert.assertEquals(0, lane.pendingCount());
    }

    /**
     * stop 与提交竞态：在 observe existing 后 stop，旧 lifecycle 不得返回 accepted。
     */
    @Test
    public void stopDuringReplaceDoesNotAcceptOnOldLifecycle() throws Exception {
        final KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(8, 64);
        lane.start();
        Assert.assertTrue(lane.submit("k", set(new AtomicInteger(), 1)));

        final CountDownLatch observedExisting = new CountDownLatch(1);
        final CountDownLatch resumeProducer = new CountDownLatch(1);
        lane.testBlockAfterObserve = observedExisting;
        lane.testResumeAfterObserve = resumeProducer;

        final AtomicBoolean accepted = new AtomicBoolean(true);
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    accepted.set(lane.submit("k", set(new AtomicInteger(), 99)));
                } catch (Throwable t) {
                    error.set(t);
                }
            }
        }, "lane-stop-race");
        producer.start();
        Assert.assertTrue(observedExisting.await(5, TimeUnit.SECONDS));
        lane.stop();
        resumeProducer.countDown();
        producer.join(5000L);
        Assert.assertFalse(producer.isAlive());
        Assert.assertNull(error.get());
        Assert.assertFalse("old lifecycle must not return accepted after stop", accepted.get());
        Assert.assertFalse(lane.isOpen());
        Assert.assertEquals(0, lane.drain());
        Assert.assertEquals(0, lane.pendingCount());
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

    /**
     * 随机并发：记录返回 true 的提交集合；生产停止后串行最终提交必须可达，
     * 且最终执行值必须属于曾 accepted 的集合（含最终提交）。
     */
    @Test
    public void afterProductionStopsLastAcceptedValueIsEventuallyReachable() throws Exception {
        KeyedLatestTaskLane<String> lane = new KeyedLatestTaskLane<String>(8, 64);
        lane.start();
        final AtomicReference<Integer> lastExecuted = new AtomicReference<Integer>();
        final AtomicInteger next = new AtomicInteger();
        final ConcurrentLinkedQueue<Integer> acceptedValues = new ConcurrentLinkedQueue<Integer>();
        final CountDownLatch producersDone = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 250; i++) {
                        final int value = next.incrementAndGet();
                        boolean accepted = lane.submit("endpoint", new Runnable() {
                            @Override
                            public void run() {
                                lastExecuted.set(Integer.valueOf(value));
                            }
                        });
                        if (accepted) {
                            acceptedValues.add(Integer.valueOf(value));
                        }
                    }
                    producersDone.countDown();
                }
            }, "producer-" + t).start();
        }
        Assert.assertTrue(producersDone.await(5, TimeUnit.SECONDS));
        Assert.assertFalse("at least one concurrent submit must be accepted", acceptedValues.isEmpty());

        // 生产停止后的串行「最后一次返回 true 的提交」必须最终可达
        final int lastAccepted = next.incrementAndGet();
        Assert.assertTrue(lane.submit("endpoint", new Runnable() {
            @Override
            public void run() {
                lastExecuted.set(Integer.valueOf(lastAccepted));
            }
        }));
        acceptedValues.add(Integer.valueOf(lastAccepted));

        int totalDrained = 0;
        while (lane.pendingCount() > 0) {
            totalDrained += lane.drain();
        }
        Assert.assertTrue(totalDrained >= 1);
        Assert.assertNotNull(lastExecuted.get());
        Assert.assertEquals(
                "last accepted submit after production stop must be the final executed value",
                lastAccepted,
                lastExecuted.get().intValue());
        Assert.assertTrue(
                "executed value must be one that returned true from submit",
                acceptedValues.contains(lastExecuted.get()));
        Assert.assertEquals(0, lane.pendingCount());
    }

    @Test
    public void staleDetectableKeyIsPurgedAndFreesCapacity() {
        KeyedLatestTaskLane<StaleKey> lane = new KeyedLatestTaskLane<StaleKey>(1, 64);
        lane.start();
        final AtomicInteger ran = new AtomicInteger();
        StaleKey stale = new StaleKey(true);
        Assert.assertTrue(lane.submit(stale, set(ran, 1)));
        Assert.assertEquals(1, lane.pendingCount());
        Assert.assertEquals(1, lane.purgeStaleKeys());
        Assert.assertEquals(0, lane.pendingCount());

        StaleKey live = new StaleKey(false);
        Assert.assertTrue(lane.submit(live, set(ran, 2)));
        Assert.assertEquals(1, lane.drain());
        Assert.assertEquals(2, ran.get());
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

    private static final class StaleKey implements KeyedLatestTaskLane.StaleDetectableKey {
        private final boolean stale;

        private StaleKey(boolean stale) {
            this.stale = stale;
        }

        @Override
        public boolean isStale() {
            return stale;
        }
    }
}
