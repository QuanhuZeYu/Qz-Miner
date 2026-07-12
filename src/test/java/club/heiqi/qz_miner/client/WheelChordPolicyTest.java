package club.heiqi.qz_miner.client;

import org.junit.Assert;
import org.junit.Test;

/** 回归滚轮组合键的纯门控策略。 */
public class WheelChordPolicyTest {

    @Test
    public void consumesOnlyCompleteInWorldChord() {
        Assert.assertTrue(WheelChordPolicy.shouldConsume(120, true, true, true, true, true));
        Assert.assertFalse(WheelChordPolicy.shouldConsume(0, true, true, true, true, true));
        Assert.assertFalse(WheelChordPolicy.shouldConsume(120, false, true, true, true, true));
        Assert.assertFalse(WheelChordPolicy.shouldConsume(120, true, false, true, true, true));
        Assert.assertFalse(WheelChordPolicy.shouldConsume(120, true, true, false, true, true));
        Assert.assertFalse(WheelChordPolicy.shouldConsume(120, true, true, true, false, true));
        Assert.assertFalse(WheelChordPolicy.shouldConsume(120, true, true, true, true, false));
    }
}
