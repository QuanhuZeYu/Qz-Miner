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
 * T17 语义类别独立契约探针（接口冻结 §D + T16a）。
 *
 * <p>独立口径来自 temp/chain-preview/verify/wave3_model.json：类别 id 冻结、
 * 非法值收窄为 UNDEFINED、本地判定为 0/1、与生产模式注册表的默认子模式表交叉一致。</p>
 */
public class SemanticClassContractTest {

    @Test
    public void frozenIdsMatchInterfaceSectionD() {
        Assert.assertEquals(0, ChainPreviewSemanticClass.PRIMARY_LOCAL);
        Assert.assertEquals(1, ChainPreviewSemanticClass.SUB_MODE_LOCAL);
        Assert.assertEquals(2, ChainPreviewSemanticClass.REMOTE_PREDICTED);
        Assert.assertEquals(3, ChainPreviewSemanticClass.TRUNCATED);
        Assert.assertEquals(4, ChainPreviewSemanticClass.DEFERRED);
        Assert.assertEquals(5, ChainPreviewSemanticClass.EXECUTED);
        Assert.assertEquals(255, ChainPreviewSemanticClass.UNDEFINED);
    }

    @Test
    public void isDefinedAndNormalizeMapIllegalValuesToUndefined() {
        for (int value = 0; value <= 5; value++) {
            Assert.assertTrue("0..5 必须为已定义类别：" + value, ChainPreviewSemanticClass.isDefined(value));
            Assert.assertEquals(value, ChainPreviewSemanticClass.normalize(value));
        }
        int[] illegal = {-1, 6, 7, 100, 254, 255, 256, 65535, Integer.MIN_VALUE, Integer.MAX_VALUE};
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

    @Test
    public void resolveLocalCoversEverySubModeWithoutLeavingUndefined() {
        Assert.assertEquals(
            "null 子模式必须判为 UNDEFINED",
            ChainPreviewSemanticClass.UNDEFINED,
            ChainPreviewSemanticClass.resolveLocal(null));

        Assert.assertEquals(
            ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.CHAIN_BASE));
        Assert.assertEquals(
            ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.AREA_SAME_BLOCK));
        Assert.assertEquals(
            ChainPreviewSemanticClass.PRIMARY_LOCAL,
            ChainPreviewSemanticClass.resolveLocal(ChainSubMode.INTERACT_BASE));

        int primaryCount = 0;
        for (ChainSubMode subMode : ChainSubMode.values()) {
            int resolved = ChainPreviewSemanticClass.resolveLocal(subMode);
            Assert.assertTrue(
                "已选子模式不得落入 UNDEFINED：" + subMode,
                resolved == ChainPreviewSemanticClass.PRIMARY_LOCAL
                    || resolved == ChainPreviewSemanticClass.SUB_MODE_LOCAL);
            if (resolved == ChainPreviewSemanticClass.PRIMARY_LOCAL) {
                primaryCount++;
            }
        }
        Assert.assertEquals("主模式默认子模式恰好 3 个（生产注册表同源）", 3, primaryCount);
    }

    @Test
    public void primaryTableMatchesRegisteredDefaultSubModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
        Assert.assertTrue(
            "子模式注册表必须已引导（否则远端判定恒 false，本交叉断言会失真）",
            ChainSubModeRegistry.usesRemotePreview(ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER));
        int checkedModes = 0;
        for (ChainMode mode : ChainMode.values()) {
            ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
            if (definition == null) {
                continue;
            }
            checkedModes++;
            ChainSubMode defaultSubMode = definition.getDefaultSubMode();
            Assert.assertNotNull("主模式必须有默认子模式：" + mode, defaultSubMode);
            // 远端子模式（如 SPECIAL/LOOTGAMES）由调用方优先判为 REMOTE_PREDICTED，
            // resolveLocal 只负责本地二分（0/1）；其余主模式默认子模式必须是 PRIMARY_LOCAL。
            int expectedDefaultClass = ChainSubModeRegistry.usesRemotePreview(defaultSubMode)
                ? ChainPreviewSemanticClass.SUB_MODE_LOCAL
                : ChainPreviewSemanticClass.PRIMARY_LOCAL;
            Assert.assertEquals(
                "默认子模式的本地判定：" + mode + "/" + defaultSubMode,
                expectedDefaultClass,
                ChainPreviewSemanticClass.resolveLocal(defaultSubMode));
            for (ChainSubMode subMode : definition.getSubModes()) {
                int expected = subMode == defaultSubMode
                    ? expectedDefaultClass
                    : ChainPreviewSemanticClass.SUB_MODE_LOCAL;
                Assert.assertEquals(
                    "非默认子模式必须判为 SUB_MODE_LOCAL：" + mode + "/" + subMode,
                    expected,
                    ChainPreviewSemanticClass.resolveLocal(subMode));
            }
        }
        Assert.assertTrue("模式注册表必须已注册（否则本断言会平凡通过）", checkedModes >= 4);
    }
}
