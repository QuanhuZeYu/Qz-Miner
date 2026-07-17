package club.heiqi.qz_miner.chain.mode;

import org.junit.Assert;
import org.junit.Test;

/** 既有 wire ordinal 0-11 与专用对象组模式删除回归。 */
public class ChainSubModeOrdinalRegressionTest {
    @Test
    public void legacyOrdinalsRemainZeroThroughEleven() {
        ChainSubMode[] values = ChainSubMode.values();
        Assert.assertEquals(12, values.length);
        for (int i = 0; i < values.length; i++) Assert.assertEquals(i, values[i].ordinal());
        Assert.assertEquals(ChainSubMode.SPECIAL_GT_CABLE_REPLACE, values[11]);
    }
}
