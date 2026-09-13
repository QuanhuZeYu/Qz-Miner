package club.heiqi.qz_miner.parallel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;

/**
 * {@link ParallelTickExecutor} 预算档位的行为合同（纯 JVM，无 MC/GL 依赖）。
 *
 * <p>覆盖：默认档窗口预算逐值等于基线、未知档安全回退、slice 档只压缩客户端 stage、
 * slice 档在任务让出后不再等满 tick 预算、slice 档跨 tick 仍持续推进、以及两个档位下
 * {@code endStage} 的 worker 屏障语义不变。</p>
 */
public class ParallelTickExecutorBudgetTest {

    private interface Scenario {
        void run() throws Exception;
    }

    private static void withBudget(String mode, int tickBudgetMs, int sliceBudgetMs, Scenario scenario)
            throws Exception {
        String previousMode = Config.parallelBudgetMode;
        int previousTickBudgetMs = Config.tickBudgetMs;
        int previousSliceBudgetMs = Config.parallelSliceBudgetMs;
        Config.parallelBudgetMode = mode;
        Config.tickBudgetMs = tickBudgetMs;
        Config.parallelSliceBudgetMs = sliceBudgetMs;
        try {
            scenario.run();
        } finally {
            Config.parallelBudgetMode = previousMode;
            Config.tickBudgetMs = previousTickBudgetMs;
            Config.parallelSliceBudgetMs = previousSliceBudgetMs;
        }
    }

    private static long windowBudgetNanos(ParallelTickExecutor executor, ParallelTickStage stage) {
        TickTimeBudget budget = executor.currentTimeBudget(stage);
        Assert.assertNotNull("窗口必须已打开", budget);
        return budget.getDeadlineNanoTime() - budget.getStartNanoTime();
    }

    @Test
    public void deadlineModeWindowBudgetMatchesBaselineTickBudget() throws Exception {
        withBudget("deadline", 40, 1, () -> {
            ParallelTickExecutor executor = new ParallelTickExecutor(false);
            try {
                executor.beginStage(ParallelTickStage.CLIENT_PRE);
                Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(40L),
                        windowBudgetNanos(executor, ParallelTickStage.CLIENT_PRE));
                executor.endStage(ParallelTickStage.CLIENT_PRE);
                Assert.assertNull(executor.currentTimeBudget(ParallelTickStage.CLIENT_PRE));
            } finally {
                executor.shutdown();
            }
        });
    }

    @Test
    public void unknownModeFallsBackToBaselineWindowBudget() throws Exception {
        withBudget("no_such_mode", 15, 1, () -> {
            ParallelTickExecutor executor = new ParallelTickExecutor(false);
            try {
                executor.beginStage(ParallelTickStage.CLIENT_PRE);
                Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(15L),
                        windowBudgetNanos(executor, ParallelTickStage.CLIENT_PRE));
                executor.endStage(ParallelTickStage.CLIENT_PRE);
            } finally {
                executor.shutdown();
            }
        });
    }

    @Test
    public void sliceModeShrinksClientStageOnly() throws Exception {
        withBudget("slice", 40, 4, () -> {
            ParallelTickExecutor executor = new ParallelTickExecutor(false);
            try {
                executor.beginStage(ParallelTickStage.CLIENT_PRE);
                Assert.assertEquals("客户端 stage 必须使用 slice 预算",
                        TimeUnit.MILLISECONDS.toNanos(4L),
                        windowBudgetNanos(executor, ParallelTickStage.CLIENT_PRE));
                executor.endStage(ParallelTickStage.CLIENT_PRE);

                executor.beginStage(ParallelTickStage.SERVER_PRE);
                Assert.assertEquals("服务端 stage 必须保持 tick 预算",
                        TimeUnit.MILLISECONDS.toNanos(40L),
                        windowBudgetNanos(executor, ParallelTickStage.SERVER_PRE));
                executor.endStage(ParallelTickStage.SERVER_PRE);
            } finally {
                executor.shutdown();
            }
        });
    }

    @Test
    public void sliceModeReleasesMainThreadAfterTaskYields() throws Exception {
        withBudget("slice", 40, 4, () -> {
            ParallelTickExecutor executor = new ParallelTickExecutor(false);
            AtomicInteger runs = new AtomicInteger();
            try {
                executor.registerClientPre("budget-yield-once", control -> {
                    runs.incrementAndGet();
                    return ParallelTaskResult.YIELDED;
                });
                executor.beginStage(ParallelTickStage.CLIENT_PRE);
                long startNanos = System.nanoTime();
                executor.endStage(ParallelTickStage.CLIENT_PRE);
                long elapsedNanos = System.nanoTime() - startNanos;
                Assert.assertTrue("任务必须获得一次调度机会", runs.get() >= 1);
                Assert.assertTrue("slice 档不得等满 tick 预算，实测 "
                                + TimeUnit.NANOSECONDS.toMillis(elapsedNanos) + "ms",
                        elapsedNanos < TimeUnit.MILLISECONDS.toNanos(20L));
            } finally {
                executor.shutdown();
            }
        });
    }

    @Test
    public void deadlineModeStillWaitsForTickBudgetAfterTaskYields() throws Exception {
        withBudget("deadline", 40, 4, () -> {
            ParallelTickExecutor executor = new ParallelTickExecutor(false);
            AtomicInteger runs = new AtomicInteger();
            try {
                executor.registerClientPre("baseline-yield-once", control -> {
                    runs.incrementAndGet();
                    return ParallelTaskResult.YIELDED;
                });
                executor.beginStage(ParallelTickStage.CLIENT_PRE);
                long startNanos = System.nanoTime();
                executor.endStage(ParallelTickStage.CLIENT_PRE);
                long elapsedNanos = System.nanoTime() - startNanos;
                Assert.assertTrue("任务必须获得一次调度机会", runs.get() >= 1);
                Assert.assertTrue("deadline 档必须保持基线：等满 tick 预算，实测 "
                                + TimeUnit.NANOSECONDS.toMillis(elapsedNanos) + "ms",
                        elapsedNanos >= TimeUnit.MILLISECONDS.toNanos(25L));
            } finally {
                executor.shutdown();
            }
        });
    }

    @Test
    public void sliceModeKeepsAdvancingOncePerTick() throws Exception {
        withBudget("slice", 40, 25, () -> {
            ParallelTickExecutor executor = new ParallelTickExecutor(false);
            AtomicInteger runs = new AtomicInteger();
            try {
                executor.registerClientPre("budget-per-tick", control -> {
                    runs.incrementAndGet();
                    return ParallelTaskResult.YIELDED;
                });
                for (int tick = 0; tick < 3; tick++) {
                    executor.beginStage(ParallelTickStage.CLIENT_PRE);
                    executor.endStage(ParallelTickStage.CLIENT_PRE);
                }
                Assert.assertEquals("每个 tick 必须恰好推进一次分片", 3, runs.get());
            } finally {
                executor.shutdown();
            }
        });
    }

    @Test
    public void endStageBarrierHoldsWhileWorkerInsideSliceInSliceMode() throws Exception {
        withBudget("slice", 40, 4, () -> {
            ParallelTickExecutor executor = new ParallelTickExecutor(false);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch endStageReturned = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread endStageThread = new Thread(() -> {
                try {
                    executor.endStage(ParallelTickStage.CLIENT_PRE);
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    endStageReturned.countDown();
                }
            }, "parallel-budget-barrier-test");
            try {
                executor.registerClientPre("budget-hold-slice", control -> {
                    entered.countDown();
                    release.await();
                    return ParallelTaskResult.COMPLETED;
                });
                executor.beginStage(ParallelTickStage.CLIENT_PRE);
                Assert.assertTrue("worker 必须进入分片", entered.await(3L, TimeUnit.SECONDS));

                endStageThread.setDaemon(true);
                endStageThread.start();
                Assert.assertFalse("worker 尚未回到安全边界时 endStage 不得返回",
                        endStageReturned.await(10L, TimeUnit.MILLISECONDS));
                Assert.assertFalse("slice 预算耗尽后主线程仍必须等待活跃 worker",
                        endStageReturned.await(10L, TimeUnit.MILLISECONDS));

                release.countDown();
                Assert.assertTrue("worker 回到安全边界后 endStage 必须返回",
                        endStageReturned.await(3L, TimeUnit.SECONDS));
                Assert.assertNull("endStage 不得抛异常", failure.get());
                Assert.assertNull(executor.currentTimeBudget(ParallelTickStage.CLIENT_PRE));
            } finally {
                release.countDown();
                executor.shutdown();
                endStageThread.join(TimeUnit.SECONDS.toMillis(3L));
            }
        });
    }
}
