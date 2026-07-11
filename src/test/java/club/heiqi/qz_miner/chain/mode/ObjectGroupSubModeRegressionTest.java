package club.heiqi.qz_miner.chain.mode;

import org.junit.Assert;
import org.junit.Test;

/** 新子模式追加语义和旧 ordinal 回归。 */
public class ObjectGroupSubModeRegressionTest {

    @Test
    public void legacyOrdinalsRemainStableAndObjectGroupIsLast() {
        Assert.assertEquals(0, ChainSubMode.CHAIN_BASE.ordinal());
        Assert.assertEquals(1, ChainSubMode.CHAIN_ORE.ordinal());
        Assert.assertEquals(2, ChainSubMode.CHAIN_LOGGING.ordinal());
        Assert.assertEquals(11, ChainSubMode.SPECIAL_GT_CABLE_REPLACE.ordinal());
        Assert.assertEquals(12, ChainSubMode.CHAIN_OBJECT_GROUP.ordinal());
        Assert.assertEquals(ChainMode.CHAIN, ChainSubMode.CHAIN_OBJECT_GROUP.getParentMode());
    }
}
