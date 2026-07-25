package club.heiqi.qz_miner.objectgroup;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/** 稳定 mode id 与 wire mask 注册表测试。 */
public class ObjectGroupModeTest {

    @Test
    public void stableIdsKeepAssignedBitsAndZeroIsLegal() {
        Assert.assertEquals(7, ObjectGroupMode.ids().length);
        Assert.assertEquals(0x007F, ObjectGroupMode.KNOWN_MASK);
        Assert.assertEquals(0L, ObjectGroupMode.toMask(Collections.emptyList()));
        Assert.assertEquals(1L, mask(ObjectGroupMode.CHAIN_BASE));
        Assert.assertEquals(2L, mask(ObjectGroupMode.CHAIN_ORE));
        Assert.assertEquals(4L, mask(ObjectGroupMode.CHAIN_LOGGING));
        Assert.assertEquals(8L, mask(ObjectGroupMode.AREA_SAME_BLOCK));
        Assert.assertEquals(16L, mask(ObjectGroupMode.AREA_ORE));
        Assert.assertEquals(32L, mask(ObjectGroupMode.INTERACT_BASE));
        Assert.assertEquals(64L, mask(ObjectGroupMode.INTERACT_CROP));
        Assert.assertEquals(127L, ObjectGroupMode.toMask(Arrays.asList(ObjectGroupMode.ids())));
        Assert.assertFalse(ObjectGroupMode.isValidMask(128L));
        Assert.assertEquals(0L,
                ObjectGroupMode.maskFor(ChainSubMode.INTERACT_LIQUID_SOURCE));
        Assert.assertEquals(0L,
                ObjectGroupMode.maskFor(ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP));
    }

    private static long mask(String mode) {
        return ObjectGroupMode.toMask(Collections.singletonList(mode));
    }
}
