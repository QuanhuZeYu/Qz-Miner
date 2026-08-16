package club.heiqi.qz_miner.parallel;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;

/** {@link ParallelTickExecutor} 独立 stage 绝对 deadline 合同。 */
public class ParallelTickExecutorDeadlineTest {

    @Test
    public void stagesExposeIndependentFrozenDeadlineWindows() {
        int previousBudget = Config.tickBudgetMs;
        Config.tickBudgetMs = 15;
        ParallelTickExecutor executor = new ParallelTickExecutor(false);
        try {
            executor.beginStage(ParallelTickStage.SERVER_PRE);
            executor.beginStage(ParallelTickStage.CLIENT_PRE);

            TickTimeBudget serverBudget = executor.currentTimeBudget(ParallelTickStage.SERVER_PRE);
            TickTimeBudget clientBudget = executor.currentTimeBudget(ParallelTickStage.CLIENT_PRE);
            Assert.assertNotNull(serverBudget);
            Assert.assertNotNull(clientBudget);
            Assert.assertNotSame(serverBudget, clientBudget);
            Assert.assertEquals(ParallelTickStage.SERVER_PRE,
                    ((ParallelTickContext) serverBudget).getStage());
            Assert.assertEquals(ParallelTickStage.CLIENT_PRE,
                    ((ParallelTickContext) clientBudget).getStage());
            Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(15L),
                    serverBudget.getDeadlineNanoTime() - serverBudget.getStartNanoTime());
            Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(15L),
                    clientBudget.getDeadlineNanoTime() - clientBudget.getStartNanoTime());

            executor.endStage(ParallelTickStage.SERVER_PRE);
            Assert.assertNull(executor.currentTimeBudget(ParallelTickStage.SERVER_PRE));
            Assert.assertNotNull(executor.currentTimeBudget(ParallelTickStage.CLIENT_PRE));
            executor.endStage(ParallelTickStage.CLIENT_PRE);
            Assert.assertNull(executor.currentTimeBudget(ParallelTickStage.CLIENT_PRE));
        } finally {
            executor.shutdown();
            Config.tickBudgetMs = previousBudget;
        }
    }

    @Test
    public void realWorkerSharesFrozenDeadlineAndRefreshesAfterEndStageBarrier() throws Exception {
        int previousBudget = Config.tickBudgetMs;
        Config.tickBudgetMs = 40;
        ParallelTickExecutor executor = new ParallelTickExecutor(false);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch endStageStarted = new CountDownLatch(1);
        CountDownLatch endStageReturned = new CountDownLatch(1);
        AtomicReference<TickTimeBudget> workerBudget = new AtomicReference<TickTimeBudget>();
        AtomicLong workerTickId = new AtomicLong(-1L);
        AtomicReference<Throwable> endStageFailure = new AtomicReference<Throwable>();
        Thread endStageThread = null;
        try {
            executor.registerPre("deadline-barrier-test", control -> {
                workerBudget.set(executor.currentTimeBudget(control.getStage()));
                workerTickId.set(control.getTickId());
                workerEntered.countDown();
                releaseWorker.await();
                return ParallelTaskResult.COMPLETED;
            });

            executor.beginStage(ParallelTickStage.SERVER_PRE);
            TickTimeBudget firstBudget = executor.currentTimeBudget(ParallelTickStage.SERVER_PRE);
            Assert.assertNotNull(firstBudget);
            Assert.assertTrue("真实 worker 必须进入首个 SERVER_PRE 窗口",
                    workerEntered.await(3L, TimeUnit.SECONDS));
            Assert.assertSame("worker 与主线程必须共享同一个冻结预算对象",
                    firstBudget, workerBudget.get());
            Assert.assertEquals(firstBudget.getTickId(), workerTickId.get());
            Assert.assertEquals(TimeUnit.MILLISECONDS.toNanos(40L),
                    firstBudget.getDeadlineNanoTime() - firstBudget.getStartNanoTime());

            endStageThread = new Thread(() -> {
                endStageStarted.countDown();
                try {
                    executor.endStage(ParallelTickStage.SERVER_PRE);
                } catch (Throwable failure) {
                    endStageFailure.set(failure);
                } finally {
                    endStageReturned.countDown();
                }
            }, "parallel-deadline-end-stage-test");
            endStageThread.setDaemon(true);
            endStageThread.start();
            Assert.assertTrue(endStageStarted.await(3L, TimeUnit.SECONDS));

            long closeWaitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3L);
            while (executor.currentTimeBudget(ParallelTickStage.SERVER_PRE) != null
                    && System.nanoTime() < closeWaitDeadline) {
                Thread.yield();
            }
            Assert.assertNull("endStage 必须先关闭窗口再等待 active worker",
                    executor.currentTimeBudget(ParallelTickStage.SERVER_PRE));
            Assert.assertEquals("worker 仍在事务内时 endStage 不得返回",
                    1L, endStageReturned.getCount());

            releaseWorker.countDown();
            Assert.assertTrue("worker 到达安全边界后 endStage 必须返回",
                    endStageReturned.await(3L, TimeUnit.SECONDS));
            if (endStageFailure.get() != null) {
                throw new AssertionError("endStage failed", endStageFailure.get());
            }
            Assert.assertNull(executor.currentTimeBudget(ParallelTickStage.SERVER_PRE));

            Config.tickBudgetMs = 7;
            executor.beginStage(ParallelTickStage.SERVER_PRE);
            TickTimeBudget nextBudget = executor.currentTimeBudget(ParallelTickStage.SERVER_PRE);
            Assert.assertNotNull(nextBudget);
            Assert.assertNotSame(firstBudget, nextBudget);
            Assert.assertTrue("新窗口必须取得新 tickId",
                    nextBudget.getTickId() > firstBudget.getTickId());
            Assert.assertEquals("新窗口必须按 beginStage 时的新配置冻结",
                    TimeUnit.MILLISECONDS.toNanos(7L),
                    nextBudget.getDeadlineNanoTime() - nextBudget.getStartNanoTime());
            executor.endStage(ParallelTickStage.SERVER_PRE);
            Assert.assertNull(executor.currentTimeBudget(ParallelTickStage.SERVER_PRE));
        } finally {
            releaseWorker.countDown();
            try {
                executor.shutdown();
            } finally {
                Config.tickBudgetMs = previousBudget;
            }
            if (endStageThread != null) {
                endStageThread.join(TimeUnit.SECONDS.toMillis(3L));
            }
        }
    }
}
