package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewRefreshPolicy;

/**
 * T12 刷新触发独立契约探针（B1.3 signal 档 + timer 等价口径）。
 *
 * <p>边界值来自独立规格模型 temp/chain-preview/verify/wave2_model.json
 * （cp_verify_wave2_model.py 产出），不复用 session-core 的断言：
 * 位移阈值与兜底时间均为「>=」触发；阈值 &lt;= 0 或 NaN 等价每帧刷新；兜底 &lt;= 0 不生效；
 * 负 elapsed 按 0 处理；位移 NaN 不得单独触发（仍由兜底保底）。</p>
 */
public class RefreshTriggerContractTest {

    private static final double THRESHOLD = 0.5D;
    private static final int FALLBACK_MS = 250;

    @Test
    public void thresholdAndFallbackBoundariesMatchIndependentModel() {
        // 模型：d=0.5 格 / f=250ms 时，只有位移达标或兜底到期才刷新。
        assertDue(false, 0.0D, THRESHOLD, 0L, FALLBACK_MS);
        assertDue(false, 0.0D, THRESHOLD, 249L, FALLBACK_MS);
        assertDue(true, 0.0D, THRESHOLD, 250L, FALLBACK_MS);
        assertDue(false, 0.4999D, THRESHOLD, 100L, FALLBACK_MS);
        assertDue(true, 0.5D, THRESHOLD, 0L, FALLBACK_MS);
        assertDue(true, 0.5001D, THRESHOLD, 0L, FALLBACK_MS);
        assertDue(true, 1000.0D, THRESHOLD, 0L, FALLBACK_MS);
    }

    @Test
    public void nonPositiveOrNaNDisplacementThresholdMeansEveryFrameRefresh() {
        assertDue(true, 0.0D, 0.0D, 0L, FALLBACK_MS);
        assertDue(true, 0.0D, -1.0D, 0L, FALLBACK_MS);
        assertDue(true, 0.0D, Double.NaN, 0L, FALLBACK_MS);
        assertDue(true, 0.0D, Double.NEGATIVE_INFINITY, 0L, 0);
    }

    @Test
    public void nonPositiveFallbackNeverTriggersByItself() {
        assertDue(false, 0.0D, THRESHOLD, 0L, 0);
        assertDue(false, 0.0D, THRESHOLD, 999999L, 0);
        assertDue(false, 0.0D, THRESHOLD, 999999L, -5);
        assertDue(true, 0.5D, THRESHOLD, 0L, 0);
    }

    @Test
    public void negativeElapsedClampsToZero() {
        assertDue(false, 0.0D, THRESHOLD, -1000L, FALLBACK_MS);
        assertDue(false, 0.4D, THRESHOLD, -1L, FALLBACK_MS);
        assertDue(true, 0.5D, THRESHOLD, -1000L, FALLBACK_MS);
    }

    @Test
    public void nanDisplacementNeverTriggersByItselfButFallbackStillDoes() {
        assertDue(false, Double.NaN, THRESHOLD, 0L, FALLBACK_MS);
        assertDue(false, Double.NaN, THRESHOLD, 249L, FALLBACK_MS);
        assertDue(true, Double.NaN, THRESHOLD, 250L, FALLBACK_MS);
    }

    @Test
    public void decisionIsMonotoneInBothInputs() {
        boolean displacementSeen = false;
        boolean previous = false;
        for (int step = 0; step <= 40; step++) {
            double displacement = step * 0.05D;
            boolean due = ChainPreviewRefreshPolicy.isSignalRefreshDue(
                displacement, THRESHOLD, 0L, FALLBACK_MS);
            Assert.assertTrue("位移增大后不得回退为不刷新", due || !previous);
            previous = due;
            displacementSeen |= due;
        }
        Assert.assertTrue("位移扫描必须至少触发一次（避免平凡通过）", displacementSeen);

        boolean previousElapsed = false;
        for (int step = 0; step <= 40; step++) {
            long elapsed = step * 20L;
            boolean due = ChainPreviewRefreshPolicy.isSignalRefreshDue(
                0.0D, THRESHOLD, elapsed, FALLBACK_MS);
            Assert.assertTrue("时间增大后不得回退为不刷新", due || !previousElapsed);
            previousElapsed = due;
        }
        Assert.assertTrue("时间扫描必须至少触发一次", previousElapsed);
    }

    @Test
    public void exactBoundaryValuesAreInclusive() {
        // 「>=」语义：阈值 0.5 与兜底 250 的等值点都必须触发。
        Assert.assertTrue(ChainPreviewRefreshPolicy.isSignalRefreshDue(
            THRESHOLD, THRESHOLD, 0L, FALLBACK_MS));
        Assert.assertTrue(ChainPreviewRefreshPolicy.isSignalRefreshDue(
            0.0D, THRESHOLD, (long) FALLBACK_MS, FALLBACK_MS));
        Assert.assertFalse(ChainPreviewRefreshPolicy.isSignalRefreshDue(
            Math.nextDown(THRESHOLD), THRESHOLD, 0L, FALLBACK_MS));
        Assert.assertFalse(ChainPreviewRefreshPolicy.isSignalRefreshDue(
            0.0D, THRESHOLD, (long) FALLBACK_MS - 1L, FALLBACK_MS));
    }

    private static void assertDue(
            boolean expected,
            double displacement,
            double threshold,
            long elapsedMillis,
            int fallbackMillis) {
        Assert.assertEquals(
            "d=" + displacement + " t=" + threshold + " e=" + elapsedMillis + " f=" + fallbackMillis,
            expected,
            ChainPreviewRefreshPolicy.isSignalRefreshDue(
                displacement, threshold, elapsedMillis, fallbackMillis));
    }
}
