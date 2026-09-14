package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;

/**
 * T17 语义类别独立契约探针（语义类别表（真源：ChainPreviewSemanticClass） + T16a；本轮按大模式重排值域）。
 *
 * <p>独立口径：类别 id 冻结（0/1/2 = 三个大模式的默认子模式，3 = 扩展子模式，4/5 = 远端/截断）、
 * 非法值收窄为 UNDEFINED、本地判定按「子模式 → 所属大模式」分派、与生产模式注册表的默认子模式表
 * 交叉一致（SPECIAL 没有默认子模式，其子模式一律落 SUB_MODE_LOCAL）。</p>
 */
public class SemanticClassContractTest {

    @Test
    public void frozenIdsMatchInterfaceSectionD() {
        Assert.assertEquals(0, ChainPreviewSemanticClass.CHAIN_LOCAL);
        Assert.assertEquals(1, ChainPreviewSemanticClass.AREA_LOCAL);
        Assert.assertEquals(2, ChainPreviewSemanticClass.INTERACT_LOCAL);
        Assert.assertEquals(3, ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        Assert.assertEquals(4, ChainPreviewSemanticClass.REMOTE_PREDICTED);
        Assert.assertEquals(5, ChainPreviewSemanticClass.TRUNCATED);
        Assert.assertEquals(6, ChainPreviewSemanticClass.DEFERRED);
        Assert.assertEquals(7, ChainPreviewSemanticClass.EXECUTED);
        Assert.assertEquals(255, ChainPreviewSemanticClass.UNDEFINED);
    }

    @Test
    public void isDefinedAndNormalizeMapIllegalValuesToUndefined() {
        for (int value = 0; value <= 7; value++) {
            Assert.assertTrue("0..7 必须为已定义类别：" + value, ChainPreviewSemanticClass.isDefined(value));
            Assert.assertEquals(value, ChainPreviewSemanticClass.normalize(value));
        }
        int[] illegal = {-1, 8, 9, 100, 254, 255, 256, 65535, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int value : illegal) {
            Assert.assertFalse("非法值不得视为已定义：" + value, ChainPreviewSemanticClass.isDefined(value));
            Assert.assertEquals(
                "非法值必须收窄为 UNDEFINED：" + value,
                ChainPreviewSemanticClass.UNDEFINED,
                ChainPreviewSemanticClass.normalize(value));
            Assert.assertEquals(
                "normalize 必须幂等：" + value,
                ChainPreviewSemanticClass.normalize(value),
                ChainPreviewSemanticClass.normalize(ChainPreviewSemanticClass.normalize(value)));
        }
    }

    /** 三个默认子模式各得不同 id（大模式可分色的前提），扩展子模式一律 SUB_MODE_LOCAL。 */
    @Test
    public void resolveLocalDispatchesByParentModeAndCoversEverySubMode() {
        Assert.assertEquals(
            "null 子模式必须判为 UNDEFINED",
            ChainPreviewSemanticClass.UNDEFINED,
            ChainPreviewSemanticClass.resolveLocal(null));

        Assert.assertEquals(
            ChainPreviewSemanticClass.CHAIN_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_BASE));
        Assert.assertEquals(
            ChainPreviewSemanticClass.AREA_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.AREA_SAME_BLOCK));
        Assert.assertEquals(
            ChainPreviewSemanticClass.INTERACT_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.INTERACT_BASE));

        int defaultCount = 0;
        for (ChainSubMode subMode : ChainSubMode.values()) {
            int resolved = ChainPreviewSemanticClass.resolveLocal(subMode);
            Assert.assertTrue(
                "已选子模式不得落入 UNDEFINED：" + subMode,
                resolved == ChainPreviewSemanticClass.CHAIN_LOCAL
                    || resolved == ChainPreviewSemanticClass.AREA_LOCAL
                    || resolved == ChainPreviewSemanticClass.INTERACT_LOCAL
                    || resolved == ChainPreviewSemanticClass.SUB_MODE_LOCAL);
            if (resolved != ChainPreviewSemanticClass.SUB_MODE_LOCAL) {
                defaultCount++;
            }
        }
        Assert.assertEquals("主模式默认子模式恰好 3 个（生产注册表同源）", 3, defaultCount);
    }

    /**
     * 默认子模式表与生产注册表交叉一致：默认子模式拿所属大模式的专属 id，非默认子模式拿
     * SUB_MODE_LOCAL；SPECIAL 没有默认子模式（其子模式都是扩展子模式）。
     */
    @Test
    public void defaultSubModeTableMatchesRegisteredDefaultSubModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
        Assert.assertTrue(
            "子模式注册表必须已引导（否则远端判定恒 false，本交叉断言会失真）",
            ChainSubModeRegistry.usesRemotePreview(ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER));
        int checkedModes = 0;
        int modesWithOwnId = 0;
        for (ChainMode mode : ChainMode.values()) {
            ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
            if (definition == null) {
                continue;
            }
            checkedModes++;
            ChainSubMode defaultSubMode = definition.getDefaultSubMode();
            if (defaultSubMode != null && ChainPreviewSemanticClass.isPrimarySubMode(defaultSubMode)) {
                Assert.assertEquals(
                    "默认子模式必须拿所属大模式的专属 id：" + mode + "/" + defaultSubMode,
                    expectedLocalIdFor(mode),
                    ChainPreviewSemanticClass.resolveLocal(defaultSubMode));
                modesWithOwnId++;
            } else if (defaultSubMode != null) {
                Assert.assertEquals(
                    "未登记的默认子模式只能判为 SUB_MODE_LOCAL：" + mode + "/" + defaultSubMode,
                    ChainPreviewSemanticClass.SUB_MODE_LOCAL,
                    ChainPreviewSemanticClass.resolveLocal(defaultSubMode));
            }
            for (ChainSubMode subMode : definition.getSubModes()) {
                if (subMode == defaultSubMode) {
                    continue;
                }
                Assert.assertEquals(
                    "非默认子模式必须判为 SUB_MODE_LOCAL：" + mode + "/" + subMode,
                    ChainPreviewSemanticClass.SUB_MODE_LOCAL,
                    ChainPreviewSemanticClass.resolveLocal(subMode));
            }
        }
        Assert.assertEquals("CHAIN / AREA / INTERACT 三个大模式各得专属 id", 3, modesWithOwnId);
        Assert.assertTrue("模式注册表必须已注册（否则本断言会平凡通过）", checkedModes >= 4);
    }

    /** 期望 id 的独立口径（不引用生产 switch，只按契约表查）。 */
    private static int expectedLocalIdFor(ChainMode mode) {
        if (mode == ChainMode.CHAIN) {
            return ChainPreviewSemanticClass.CHAIN_LOCAL;
        }
        if (mode == ChainMode.AREA) {
            return ChainPreviewSemanticClass.AREA_LOCAL;
        }
        if (mode == ChainMode.INTERACT) {
            return ChainPreviewSemanticClass.INTERACT_LOCAL;
        }
        return ChainPreviewSemanticClass.SUB_MODE_LOCAL;
    }
}
