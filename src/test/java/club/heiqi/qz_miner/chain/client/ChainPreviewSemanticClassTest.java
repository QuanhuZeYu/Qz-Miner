package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/** B2.3 a：语义类别 id（接口冻结 §D）与本地类别判定的契约。 */
public class ChainPreviewSemanticClassTest {

    @Test
    public void frozenIdsMatchInterfaceFreeze() {
        Assert.assertEquals(0, ChainPreviewSemanticClass.PRIMARY_LOCAL);
        Assert.assertEquals(1, ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        Assert.assertEquals(2, ChainPreviewSemanticClass.REMOTE_PREDICTED);
        Assert.assertEquals(3, ChainPreviewSemanticClass.TRUNCATED);
        Assert.assertEquals(4, ChainPreviewSemanticClass.DEFERRED);
        Assert.assertEquals(5, ChainPreviewSemanticClass.EXECUTED);
        Assert.assertEquals(255, ChainPreviewSemanticClass.UNDEFINED);

        Assert.assertTrue(ChainPreviewSemanticClass.isDefined(0));
        Assert.assertTrue(ChainPreviewSemanticClass.isDefined(5));
        Assert.assertFalse(ChainPreviewSemanticClass.isDefined(255));
        Assert.assertFalse("负数不是合法类别", ChainPreviewSemanticClass.isDefined(-1));
    }

    @Test
    public void normalizeMapsIllegalValuesToUndefined() {
        Assert.assertEquals(0, ChainPreviewSemanticClass.normalize(0));
        Assert.assertEquals(5, ChainPreviewSemanticClass.normalize(5));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(255));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(6));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(-7));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(Integer.MIN_VALUE));
    }

    @Test
    public void localResolutionSplitsPrimaryDefaultsFromExtendedSubModes() {
        Assert.assertEquals("null 子模式无法判定",
            ChainPreviewSemanticClass.UNDEFINED, ChainPreviewSemanticClass.resolveLocal(null));

        Assert.assertEquals(ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_BASE));
        Assert.assertEquals(ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.AREA_SAME_BLOCK));
        Assert.assertEquals(ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.INTERACT_BASE));

        Assert.assertEquals(ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_ORE));
        Assert.assertEquals(ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_LOGGING));
        Assert.assertEquals(ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.AREA_TUNNEL));
        Assert.assertEquals(ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.INTERACT_CROP));
        Assert.assertEquals(ChainPreviewSemanticClass.SUB_MODE_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.SPECIAL_GT_CABLE_REPLACE));
    }

    @Test
    public void everySubModeResolvesToDefinedLocalClassOrUndefined() {
        for (ChainSubMode subMode : ChainSubMode.values()) {
            int resolved = ChainPreviewSemanticClass.resolveLocal(subMode);
            Assert.assertTrue("每个子模式都必须有确定类别：" + subMode,
                resolved == ChainPreviewSemanticClass.PRIMARY_LOCAL
                    || resolved == ChainPreviewSemanticClass.SUB_MODE_LOCAL
                    || resolved == ChainPreviewSemanticClass.UNDEFINED);
        }
    }
}
