package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/** B2.3 a：语义类别 id（接口冻结 §D，本轮按大模式重排）与本地类别判定的契约。 */
public class ChainPreviewSemanticClassTest {

    @Test
    public void frozenIdsMatchInterfaceFreeze() {
        Assert.assertEquals(0, ChainPreviewSemanticClass.CHAIN_LOCAL);
        Assert.assertEquals(1, ChainPreviewSemanticClass.AREA_LOCAL);
        Assert.assertEquals(2, ChainPreviewSemanticClass.INTERACT_LOCAL);
        Assert.assertEquals(3, ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        Assert.assertEquals(4, ChainPreviewSemanticClass.REMOTE_PREDICTED);
        Assert.assertEquals(5, ChainPreviewSemanticClass.TRUNCATED);
        Assert.assertEquals(6, ChainPreviewSemanticClass.DEFERRED);
        Assert.assertEquals(7, ChainPreviewSemanticClass.EXECUTED);
        Assert.assertEquals(255, ChainPreviewSemanticClass.UNDEFINED);

        Assert.assertTrue(ChainPreviewSemanticClass.isDefined(0));
        Assert.assertTrue(ChainPreviewSemanticClass.isDefined(7));
        Assert.assertFalse(ChainPreviewSemanticClass.isDefined(255));
        Assert.assertFalse("重排后 8 不是合法类别", ChainPreviewSemanticClass.isDefined(8));
        Assert.assertFalse("负数不是合法类别", ChainPreviewSemanticClass.isDefined(-1));
    }

    @Test
    public void normalizeMapsIllegalValuesToUndefined() {
        Assert.assertEquals(0, ChainPreviewSemanticClass.normalize(0));
        Assert.assertEquals(7, ChainPreviewSemanticClass.normalize(7));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(255));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(8));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(-7));
        Assert.assertEquals(255, ChainPreviewSemanticClass.normalize(Integer.MIN_VALUE));
    }

    /**
     * 三个大模式的默认子模式各得**不同** id（本任务的核心诉求：默认颜色按大模式区分），
     * 其余子模式（含 SPECIAL 两个）一律 SUB_MODE_LOCAL。
     */
    @Test
    public void defaultSubModesOfEachBigModeGetTheirOwnId() {
        Assert.assertEquals("null 子模式无法判定",
            ChainPreviewSemanticClass.UNDEFINED, ChainPreviewSemanticClass.resolveLocal(null));

        Assert.assertEquals(ChainPreviewSemanticClass.CHAIN_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_BASE));
        Assert.assertEquals(ChainPreviewSemanticClass.AREA_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.AREA_SAME_BLOCK));
        Assert.assertEquals(ChainPreviewSemanticClass.INTERACT_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.INTERACT_BASE));

        Assert.assertNotEquals("CHAIN 与 AREA 的默认子模式不得共用 id",
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_BASE),
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.AREA_SAME_BLOCK));
        Assert.assertNotEquals("AREA 与 INTERACT 的默认子模式不得共用 id",
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.AREA_SAME_BLOCK),
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.INTERACT_BASE));
        Assert.assertNotEquals("CHAIN 与 INTERACT 的默认子模式不得共用 id",
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_BASE),
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.INTERACT_BASE));

        // 每个默认子模式的 id 必须与其所属大模式一致（分派真源是子模式 → 父模式）
        Assert.assertEquals(ChainMode.CHAIN, ChainSubMode.CHAIN_BASE.getParentMode());
        Assert.assertEquals(ChainMode.AREA, ChainSubMode.AREA_SAME_BLOCK.getParentMode());
        Assert.assertEquals(ChainMode.INTERACT, ChainSubMode.INTERACT_BASE.getParentMode());
    }

    @Test
    public void extendedSubModesIncludingSpecialFallBackToSubModeLocal() {
        ChainSubMode[] extended = {
            ChainSubMode.CHAIN_ORE,
            ChainSubMode.CHAIN_LOGGING,
            ChainSubMode.AREA_HARVESTABLE_ALL,
            ChainSubMode.AREA_ORE,
            ChainSubMode.AREA_TUNNEL,
            ChainSubMode.AREA_SECTION_CLEAR,
            ChainSubMode.AREA_CUBOID_CLEAR,
            ChainSubMode.INTERACT_CROP,
            ChainSubMode.INTERACT_LIQUID_SOURCE,
            ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP,
            ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER,
            ChainSubMode.SPECIAL_GT_CABLE_REPLACE};
        for (ChainSubMode subMode : extended) {
            Assert.assertEquals("扩展子模式必须判为 SUB_MODE_LOCAL：" + subMode,
                ChainPreviewSemanticClass.SUB_MODE_LOCAL,
                ChainPreviewSemanticClass.resolveLocal(subMode));
        }
    }

    @Test
    public void everySubModeResolvesToADefinedLocalClass() {
        for (ChainSubMode subMode : ChainSubMode.values()) {
            int resolved = ChainPreviewSemanticClass.resolveLocal(subMode);
            Assert.assertTrue("每个子模式都必须有确定类别：" + subMode,
                resolved == ChainPreviewSemanticClass.CHAIN_LOCAL
                    || resolved == ChainPreviewSemanticClass.AREA_LOCAL
                    || resolved == ChainPreviewSemanticClass.INTERACT_LOCAL
                    || resolved == ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        }
    }
}
