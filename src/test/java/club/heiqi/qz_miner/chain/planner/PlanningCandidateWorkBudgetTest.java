package club.heiqi.qz_miner.chain.planner;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.parallel.ParallelTickControl;
import club.heiqi.qz_miner.parallel.ParallelTickStage;

/** {@link PlanningCandidateWorkBudget} 的 1024:1 边界与恢复测试。 */
public class PlanningCandidateWorkBudgetTest {

    private static final ChainTarget TARGET = new ChainTarget(1, 2, 3);

    /** 固定配额在 1023/1024/1025/2048 边界产生精确商与余数。 */
    @Test
    public void airRatioUsesExactQuotientAndRemainder() {
        assertAirRatio(1023, 0, 1023);
        assertAirRatio(1024, 1, 0);
        assertAirRatio(1025, 1, 1);
        assertAirRatio(2048, 2, 0);
    }

    /** 空气余数归 context 级计费器所有，可跨任意 slice 延续。 */
    @Test
    public void airRemainderSurvivesSliceBoundaries() {
        PlanningCandidateWorkBudget budget = new PlanningCandidateWorkBudget();
        TestControl control = new TestControl(1, true);
        int committed = 0;
        int consumed = 0;
        int slices = 1;

        while (committed < 2049) {
            PlanningCandidateWorkBudget.CommitResult result =
                    budget.tryCommit(control, TARGET, target -> true);
            if (result == PlanningCandidateWorkBudget.CommitResult.YIELDED) {
                consumed += control.getConsumed();
                control = new TestControl(1, true);
                slices++;
                continue;
            }
            Assert.assertEquals(PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED, result);
            committed++;
        }
        consumed += control.getConsumed();

        Assert.assertTrue("必须实际跨越多个 slice", slices > 1);
        Assert.assertEquals(2, consumed);
        Assert.assertEquals(1, budget.getAirRemainder());
    }

    /** 第 1024 个空气扣费失败时不提交；重试必须重读世界并按新事实计费。 */
    @Test
    public void quotaBoundaryFailureDoesNotAdvanceAndRereadsWorld() {
        PlanningCandidateWorkBudget budget = new PlanningCandidateWorkBudget();
        TestControl warmup = new TestControl(4, true);
        for (int index = 0; index < 1023; index++) {
            Assert.assertEquals(
                    PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED,
                    budget.tryCommit(warmup, TARGET, target -> true));
        }

        AtomicBoolean observedAir = new AtomicBoolean(true);
        AtomicInteger probes = new AtomicInteger();
        TestControl unavailable = new TestControl(0, false);
        PlanningCandidateWorkBudget.CommitResult failed = budget.tryCommit(
                unavailable, TARGET, target -> {
                    probes.incrementAndGet();
                    return observedAir.get();
                });

        Assert.assertEquals(PlanningCandidateWorkBudget.CommitResult.YIELDED, failed);
        Assert.assertEquals(1023, budget.getAirRemainder());
        Assert.assertEquals(1, probes.get());
        Assert.assertEquals(0, unavailable.getConsumed());

        observedAir.set(false);
        TestControl retry = new TestControl(1, true);
        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.NORMAL_COMMITTED,
                budget.tryCommit(retry, TARGET, target -> {
                    probes.incrementAndGet();
                    return observedAir.get();
                }));
        Assert.assertEquals("失败空气不得提前改变余数", 1023, budget.getAirRemainder());
        Assert.assertEquals(2, probes.get());
        Assert.assertEquals(1, retry.getConsumed());

        TestControl nextAir = new TestControl(1, true);
        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED,
                budget.tryCommit(nextAir, TARGET, target -> true));
        Assert.assertEquals(0, budget.getAirRemainder());
        Assert.assertEquals(1, nextAir.getConsumed());
    }

    /** 取消或窗口/预算边界必须先于世界探测，不能额外偷跑免费空气。 */
    @Test
    public void controlBoundaryPrecedesBlockProbe() {
        PlanningCandidateWorkBudget budget = new PlanningCandidateWorkBudget();
        AtomicInteger probes = new AtomicInteger();

        TestControl cancelled = new TestControl(1, true);
        cancelled.cancelled = true;
        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.TERMINATED,
                budget.tryCommit(cancelled, TARGET, target -> {
                    probes.incrementAndGet();
                    return true;
                }));

        TestControl closedWindow = new TestControl(1, true);
        closedWindow.forcedYield = true;
        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.YIELDED,
                budget.tryCommit(closedWindow, TARGET, target -> {
                    probes.incrementAndGet();
                    return true;
                }));

        TestControl exhausted = new TestControl(0, true);
        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.YIELDED,
                budget.tryCommit(exhausted, TARGET, target -> {
                    probes.incrementAndGet();
                    return true;
                }));

        Assert.assertEquals(0, probes.get());
        Assert.assertEquals(0, budget.getAirRemainder());
    }

    /** 非空气与 null 均保持逐候选正常收费，且不能消耗或清除空气余数。 */
    @Test
    public void nonAirCandidatesKeepNormalCharges() {
        PlanningCandidateWorkBudget budget = new PlanningCandidateWorkBudget();
        TestControl control = new TestControl(4, true);

        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED,
                budget.tryCommit(control, TARGET, target -> true));
        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.NORMAL_COMMITTED,
                budget.tryCommit(control, TARGET, target -> false));
        Assert.assertEquals(
                PlanningCandidateWorkBudget.CommitResult.NORMAL_COMMITTED,
                budget.tryCommit(control, TARGET, target -> false));

        Assert.assertEquals(2, control.getConsumed());
        Assert.assertEquals(1, budget.getAirRemainder());
    }

    private static void assertAirRatio(int count, int expectedUnits, int expectedRemainder) {
        PlanningCandidateWorkBudget budget = new PlanningCandidateWorkBudget();
        TestControl control = new TestControl(Math.max(4, expectedUnits + 1), true);
        for (int index = 0; index < count; index++) {
            Assert.assertEquals(
                    PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED,
                    budget.tryCommit(control, TARGET, target -> true));
        }
        Assert.assertEquals(expectedUnits, control.getConsumed());
        Assert.assertEquals(expectedRemainder, budget.getAirRemainder());
    }

    /** 可分别控制 shouldYield 与 tryConsumeWork，覆盖配额提交失败边界。 */
    private static final class TestControl implements ParallelTickControl {
        private final boolean yieldWhenEmpty;
        private int remaining;
        private int consumed;
        private boolean cancelled;
        private boolean forcedYield;

        private TestControl(int workBudget, boolean yieldWhenEmpty) {
            this.remaining = Math.max(0, workBudget);
            this.yieldWhenEmpty = yieldWhenEmpty;
        }

        int getConsumed() {
            return consumed;
        }

        @Override public long getTickId() { return 1L; }
        @Override public ParallelTickStage getStage() { return ParallelTickStage.SERVER_PRE; }
        @Override public boolean isWindowOpen() { return !forcedYield; }
        @Override public boolean isCancelRequested() { return cancelled; }
        @Override public boolean shouldYield() { return forcedYield || yieldWhenEmpty && remaining <= 0; }

        @Override
        public boolean tryConsumeWork(int units) {
            if (units <= 0) return true;
            if (cancelled || forcedYield || units > remaining) return false;
            remaining -= units;
            consumed += units;
            return true;
        }

        @Override public long getElapsedNanoTime() { return 0L; }
        @Override public String getCancelReason() { return cancelled ? "test" : ""; }
    }
}
