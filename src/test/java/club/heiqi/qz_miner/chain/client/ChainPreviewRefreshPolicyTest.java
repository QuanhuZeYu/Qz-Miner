package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

/** B1.3 刷新决策公开纯函数的契约（verify 包可直连调用同一入口做独立断言）。 */
public class ChainPreviewRefreshPolicyTest {

    @Test
    public void thresholdHitRefreshesWhileInsufficientDisplacementWithinFallbackDoesNot() {
        Assert.assertFalse("位移不足且未超兜底不得刷新",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.4D, 0.5D, 100L, 250));
        Assert.assertTrue("位移达标（含等号）必须刷新",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.5D, 0.5D, 100L, 250));
        Assert.assertTrue(ChainPreviewRefreshPolicy.isSignalRefreshDue(0.6D, 0.5D, 100L, 250));

        Assert.assertFalse("249ms 未到兜底",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.0D, 0.5D, 249L, 250));
        Assert.assertTrue("250ms 到兜底必须刷新",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.0D, 0.5D, 250L, 250));

        Assert.assertFalse("位移 NaN 不得误判达标",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(Double.NaN, 0.5D, 100L, 250));
        Assert.assertTrue("位移 NaN 仍由兜底保底",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(Double.NaN, 0.5D, 250L, 250));
    }

    @Test
    public void degenerateThresholdAndFallbackValuesConvergeSafely() {
        Assert.assertTrue("阈值 0 等价每帧刷新",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.0D, 0.0D, 0L, 250));
        Assert.assertTrue("阈值负值收窄为每帧刷新",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.0D, -1.0D, 0L, 250));
        Assert.assertTrue("阈值 NaN 收窄为每帧刷新",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.0D, Double.NaN, 0L, 250));

        Assert.assertFalse("兜底 0 表示不生效，只由位移触发",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.4D, 0.5D, Long.MAX_VALUE / 2L, 0));
        Assert.assertFalse("兜底负值同样不生效",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.4D, 0.5D, Long.MAX_VALUE / 2L, -5));
        Assert.assertFalse("负 elapsed 按 0 处理，不得误触发兜底",
            ChainPreviewRefreshPolicy.isSignalRefreshDue(0.4D, 0.5D, -100L, 250));
    }
}
