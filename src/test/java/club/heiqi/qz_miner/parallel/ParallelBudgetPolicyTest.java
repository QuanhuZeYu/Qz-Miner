package club.heiqi.qz_miner.parallel;

import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Test;

/** {@link ParallelBudgetPolicy} 档位语义、stage 适用范围与主线程等待边界的独立合同。 */
public class ParallelBudgetPolicyTest {

    private static long millis(long value) {
        return TimeUnit.MILLISECONDS.toNanos(value);
    }

    @Test
    public void unknownModeIdsFallBackToBaseline() {
        Assert.assertEquals(ParallelBudgetMode.DEADLINE, ParallelBudgetMode.fromId(null));
        Assert.assertEquals(ParallelBudgetMode.DEADLINE, ParallelBudgetMode.fromId(""));
        Assert.assertEquals(ParallelBudgetMode.DEADLINE, ParallelBudgetMode.fromId("   "));
        Assert.assertEquals(ParallelBudgetMode.DEADLINE, ParallelBudgetMode.fromId("no_such_mode"));
        Assert.assertEquals(ParallelBudgetMode.DEADLINE, ParallelBudgetMode.fromId("DEADLINE"));
        Assert.assertEquals(ParallelBudgetMode.SLICE, ParallelBudgetMode.fromId("slice"));
        Assert.assertEquals(ParallelBudgetMode.SLICE, ParallelBudgetMode.fromId("  SLICE  "));
        Assert.assertEquals(ParallelBudgetMode.DEADLINE, ParallelBudgetMode.DEFAULT);
    }

    @Test
    public void declaredIdsMatchSchemaSurface() {
        Assert.assertArrayEquals(new String[] {"deadline", "slice"}, ParallelBudgetMode.ids());
        Assert.assertEquals("enum id 必须与 config 真源逐字节一致",
                club.heiqi.qz_miner.config.QzMinerConfigDefaults.PARALLEL_BUDGET_MODES,
                java.util.Arrays.asList(ParallelBudgetMode.ids()));
        Assert.assertEquals(club.heiqi.qz_miner.config.QzMinerConfigDefaults.PARALLEL_BUDGET_MODE,
                ParallelBudgetMode.DEFAULT.id());
    }

    @Test
    public void sliceBudgetAppliesToClientStagesOnly() {
        Assert.assertTrue(ParallelBudgetPolicy.usesSliceBudget(
                ParallelBudgetMode.SLICE, ParallelTickStage.CLIENT_PRE));
        Assert.assertTrue(ParallelBudgetPolicy.usesSliceBudget(
                ParallelBudgetMode.SLICE, ParallelTickStage.CLIENT_POST));
        Assert.assertFalse(ParallelBudgetPolicy.usesSliceBudget(
                ParallelBudgetMode.SLICE, ParallelTickStage.SERVER_PRE));
        Assert.assertFalse(ParallelBudgetPolicy.usesSliceBudget(
                ParallelBudgetMode.SLICE, ParallelTickStage.SERVER_POST));
        Assert.assertFalse(ParallelBudgetPolicy.usesSliceBudget(
                ParallelBudgetMode.DEADLINE, ParallelTickStage.CLIENT_PRE));
        Assert.assertFalse(ParallelBudgetPolicy.usesSliceBudget(null, ParallelTickStage.CLIENT_PRE));
        Assert.assertFalse(ParallelBudgetPolicy.usesSliceBudget(ParallelBudgetMode.SLICE, null));
    }

    @Test
    public void baselineBudgetFreezesTickBudgetOnly() {
        Assert.assertEquals(millis(15L), ParallelBudgetPolicy.windowBudgetNanos(false, 15, 4));
        Assert.assertEquals(millis(15L), ParallelBudgetPolicy.windowBudgetNanos(false, 15, 1));
        Assert.assertEquals(millis(1L), ParallelBudgetPolicy.windowBudgetNanos(false, 0, 4));
        Assert.assertEquals(millis(40L), ParallelBudgetPolicy.windowBudgetNanos(false, 999, 4));
    }

    @Test
    public void sliceBudgetCapsWindowToSliceBudget() {
        Assert.assertEquals(millis(4L), ParallelBudgetPolicy.windowBudgetNanos(true, 15, 4));
        Assert.assertEquals(millis(1L), ParallelBudgetPolicy.windowBudgetNanos(true, 15, 0));
        Assert.assertEquals(millis(15L), ParallelBudgetPolicy.windowBudgetNanos(true, 15, 999));
        Assert.assertEquals(millis(3L), ParallelBudgetPolicy.windowBudgetNanos(true, 3, 10));
        Assert.assertEquals(millis(40L), ParallelBudgetPolicy.windowBudgetNanos(true, 999, 999));
    }

    @Test
    public void noRegisteredTaskNeverWaits() {
        Assert.assertFalse(ParallelBudgetPolicy.shouldMainThreadWait(true, 0, 1, 0, 0L, millis(15L)));
        Assert.assertFalse(ParallelBudgetPolicy.shouldMainThreadWait(false, 0, 0, 0, 0L, millis(15L)));
    }

    @Test
    public void expiredWindowNeverWaits() {
        Assert.assertFalse(ParallelBudgetPolicy.shouldMainThreadWait(true, 2, 1, 0, millis(15L), millis(15L)));
        Assert.assertFalse(ParallelBudgetPolicy.shouldMainThreadWait(false, 2, 1, 2, millis(16L), millis(15L)));
    }

    @Test
    public void activeSliceAlwaysKeepsMainThreadWaiting() {
        Assert.assertTrue(ParallelBudgetPolicy.shouldMainThreadWait(true, 3, 1, 3, 0L, millis(4L)));
        Assert.assertTrue(ParallelBudgetPolicy.shouldMainThreadWait(false, 3, 2, 3, 0L, millis(15L)));
    }

    @Test
    public void sliceReturnsOnceEveryRegisteredTaskGotItsTurn() {
        Assert.assertTrue(ParallelBudgetPolicy.shouldMainThreadWait(true, 2, 0, 1, 0L, millis(4L)));
        Assert.assertFalse(ParallelBudgetPolicy.shouldMainThreadWait(true, 2, 0, 2, 0L, millis(4L)));
        Assert.assertFalse(ParallelBudgetPolicy.shouldMainThreadWait(true, 1, 0, 1, 0L, millis(4L)));
    }

    @Test
    public void baselineKeepsWaitingUntilDeadlineAfterTasksYielded() {
        Assert.assertTrue(ParallelBudgetPolicy.shouldMainThreadWait(false, 1, 0, 1, 0L, millis(15L)));
        Assert.assertTrue(ParallelBudgetPolicy.shouldMainThreadWait(false, 2, 0, 2, millis(14L), millis(15L)));
    }
}
