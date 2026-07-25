package club.heiqi.qz_miner.toolswap;

import org.junit.Assert;
import org.junit.Test;

/** 自动工具资格只以收获结论和耐久储备为硬门。 */
public class AutoToolUsabilityPolicyTest {

    @Test
    public void lowEfficiencyDoesNotRejectHarvestableTool() {
        Assert.assertTrue(AutoToolUsabilityPolicy.canContinue(false, true, 2));
        Assert.assertTrue(AutoToolUsabilityPolicy.canContinue(false, true, Integer.MAX_VALUE));
    }

    @Test
    public void harvestAndDurabilityRemainRequired() {
        Assert.assertFalse(AutoToolUsabilityPolicy.canContinue(true, false, 100));
        Assert.assertFalse(AutoToolUsabilityPolicy.canContinue(true, true, 1));
        Assert.assertTrue(AutoToolUsabilityPolicy.canContinue(true, true, 2));
    }
}
