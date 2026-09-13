package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/** B1.2：目标抖动稳定窗口的边界契约（默认 2 tick）。 */
public class PreviewTargetDebounceTest {

    @Test
    public void candidateMustStayStableForRequiredTicks() {
        PreviewTargetDebounce debounce = new PreviewTargetDebounce();
        ChainTarget first = new ChainTarget(0, 0, 0);

        Assert.assertFalse("首次出现必须再等一 tick", debounce.shouldAccept(first, 2));
        Assert.assertEquals(1, debounce.getStableTicks());
        Assert.assertTrue("连续第 2 tick 稳定才接受", debounce.shouldAccept(first, 2));
        Assert.assertEquals(2, debounce.getStableTicks());
        Assert.assertTrue("继续稳定保持接受", debounce.shouldAccept(first, 2));
    }

    @Test
    public void jitterResetsTheWindowAndNeverAcceptsOnFirstAppearance() {
        PreviewTargetDebounce debounce = new PreviewTargetDebounce();
        ChainTarget a = new ChainTarget(0, 0, 0);
        ChainTarget b = new ChainTarget(1, 0, 0);

        Assert.assertFalse(debounce.shouldAccept(b, 2));
        Assert.assertFalse("抖动到另一目标必须重新计时", debounce.shouldAccept(a, 2));
        Assert.assertEquals(1, debounce.getStableTicks());
        Assert.assertTrue(debounce.shouldAccept(a, 2));

        debounce.reset();
        Assert.assertNull(debounce.getCandidate());
        Assert.assertEquals(0, debounce.getStableTicks());
        Assert.assertFalse("reset 后重新计时", debounce.shouldAccept(a, 2));
    }

    @Test
    public void degenerateRequiredTicksAndNullTargetConvergeSafely() {
        PreviewTargetDebounce debounce = new PreviewTargetDebounce();
        ChainTarget target = new ChainTarget(2, 2, 2);

        Assert.assertTrue("requiredTicks<=1 立即接受", debounce.shouldAccept(target, 1));
        Assert.assertTrue("requiredTicks=0 立即接受", debounce.shouldAccept(target, 0));
        Assert.assertTrue("requiredTicks 负数立即接受", debounce.shouldAccept(target, -3));

        Assert.assertFalse("null 目标直接拒绝并复位", debounce.shouldAccept(null, 2));
        Assert.assertNull(debounce.getCandidate());
        Assert.assertEquals(0, debounce.getStableTicks());
    }
}
