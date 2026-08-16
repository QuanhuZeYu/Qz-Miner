package club.heiqi.qz_miner.chain.planner;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;

/** {@link PlanningCandidateGate} 的 soft deadline 候选事务测试。 */
public class PlanningCandidateGateTest {

    private static final ChainTarget TARGET = new ChainTarget(1, 2, 3);

    @Test
    public void openWindowCommitsObservedAirAndNormalBlocks() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();

        Assert.assertEquals(PlanningCandidateGate.CommitResult.AIR_COMMITTED,
                gate.tryCommit(control, TARGET, target -> true));
        Assert.assertEquals(PlanningCandidateGate.CommitResult.NORMAL_COMMITTED,
                gate.tryCommit(control, TARGET, target -> false));
    }

    @Test
    public void expiredDeadlinePrecedesWorldRead() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();
        AtomicInteger probes = new AtomicInteger();
        control.expire();

        Assert.assertEquals(PlanningCandidateGate.CommitResult.YIELDED,
                gate.tryCommit(control, TARGET, target -> {
                    probes.incrementAndGet();
                    return true;
                }));
        Assert.assertEquals(0, probes.get());
    }

    @Test
    public void cancellationPrecedesWorldRead() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();
        AtomicInteger probes = new AtomicInteger();
        control.cancel();

        Assert.assertEquals(PlanningCandidateGate.CommitResult.TERMINATED,
                gate.tryCommit(control, TARGET, target -> {
                    probes.incrementAndGet();
                    return true;
                }));
        Assert.assertEquals(0, probes.get());
    }

    @Test
    public void deadlineExpiringDuringReadCommitsObservedFactOnce() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();
        AtomicInteger probes = new AtomicInteger();

        Assert.assertEquals(PlanningCandidateGate.CommitResult.AIR_COMMITTED,
                gate.tryCommit(control, TARGET, target -> {
                    probes.incrementAndGet();
                    control.expire();
                    return true;
                }));
        Assert.assertEquals("已开始的读取必须提交一次，不能按窗口永久重试", 1, probes.get());
    }

    @Test
    public void cancellationObservedAfterReadTerminatesTransaction() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();
        AtomicInteger probes = new AtomicInteger();

        Assert.assertEquals(PlanningCandidateGate.CommitResult.TERMINATED,
                gate.tryCommit(control, TARGET, target -> {
                    probes.incrementAndGet();
                    control.cancel();
                    return false;
                }));
        Assert.assertEquals(1, probes.get());
    }

    @Test
    public void expiredDeadlinePrecedesCandidateFilter() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();
        AtomicInteger filterCalls = new AtomicInteger();
        control.expire();

        Assert.assertEquals(PlanningCandidateGate.FilterResult.YIELDED,
                gate.tryCommitFilter(control, TARGET, target -> {
                    filterCalls.incrementAndGet();
                    return true;
                }));
        Assert.assertEquals(0, filterCalls.get());
    }

    @Test
    public void deadlineExpiringDuringFilterCommitsReturnedFactOnce() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();
        AtomicInteger filterCalls = new AtomicInteger();

        Assert.assertEquals(PlanningCandidateGate.FilterResult.ACCEPTED,
                gate.tryCommitFilter(control, TARGET, target -> {
                    filterCalls.incrementAndGet();
                    control.expire();
                    return true;
                }));
        Assert.assertEquals(1, filterCalls.get());
    }

    @Test
    public void cancellationObservedAfterFilterPreventsStateCommit() {
        PlanningCandidateGate gate = new PlanningCandidateGate();
        TestControl control = new TestControl();
        AtomicInteger filterCalls = new AtomicInteger();

        Assert.assertEquals(PlanningCandidateGate.FilterResult.TERMINATED,
                gate.tryCommitFilter(control, TARGET, target -> {
                    filterCalls.incrementAndGet();
                    control.cancel();
                    return true;
                }));
        Assert.assertEquals(1, filterCalls.get());
    }

    private static final class TestControl implements ParallelTickControl {
        private boolean expired;
        private boolean cancelled;

        void expire() { expired = true; }
        void cancel() { cancelled = true; }

        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return !expired; }
        @Override public boolean isCancelRequested() { return cancelled; }
        @Override public boolean shouldYield() { return cancelled || expired; }
        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return cancelled ? "test" : ""; }
    }
}
