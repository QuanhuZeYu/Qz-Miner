package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

/**
 * T34 / B2.5 原版高亮协同纯决策边界：三条件全真才抑制；开关关闭、预览未激活、
 * 瞄准目标不匹配（含瞄准空气、origin 未建立）一律 fail-open。
 */
public class ChainPreviewVanillaHighlightTest {

    @Test
    public void eightBoundaryCombinationsOnlySuppressWhenAllThreeHold() {
        // 默认档（开关 false）：全 4 组组合都不得抑制 = 关闭时零原版行为改动
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(false, false, false));
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(false, false, true));
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(false, true, false));
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(false, true, true));

        // 开启但预览未激活：全 2 组都不得抑制
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(true, false, false));
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(true, false, true));

        // 开启且激活但瞄准目标不匹配（含瞄准空气 / 其它方块）：不得抑制
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(true, true, false));

        // 三条件同时满足：抑制
        Assert.assertTrue(ChainPreviewVanillaHighlight.shouldSuppress(true, true, true));
    }

    @Test
    public void exhaustiveTruthTableMatchesConjunctionAndIsRepeatable() {
        boolean[] values = { false, true };
        int checked = 0;
        for (boolean suppress : values) {
            for (boolean active : values) {
                for (boolean matched : values) {
                    boolean expected = suppress && active && matched;
                    Assert.assertEquals(
                        "组合 " + suppress + "/" + active + "/" + matched,
                        expected,
                        ChainPreviewVanillaHighlight.shouldSuppress(suppress, active, matched));
                    Assert.assertEquals(
                        "纯函数必须可重复",
                        expected,
                        ChainPreviewVanillaHighlight.shouldSuppress(suppress, active, matched));
                    checked++;
                }
            }
        }
        Assert.assertEquals(8, checked);
    }

    @Test
    public void suppressionRequiresExplicitSwitchOn() {
        Assert.assertFalse(
            "开关关闭时无条件 fail-open（不清空 / 不改状态）",
            ChainPreviewVanillaHighlight.shouldSuppress(false, true, true));
    }

    @Test
    public void originMatchRequiresEstablishedOriginAndExactCoordinates() {
        Assert.assertTrue(ChainPreviewVanillaHighlight.matchesOrigin(true, 10, 64, -30, 10, 64, -30));
        Assert.assertFalse("origin 未建立不得匹配", ChainPreviewVanillaHighlight.matchesOrigin(
            false, 10, 64, -30, 10, 64, -30));
        Assert.assertFalse("X 不同不匹配", ChainPreviewVanillaHighlight.matchesOrigin(
            true, 10, 64, -30, 11, 64, -30));
        Assert.assertFalse("Y 不同不匹配", ChainPreviewVanillaHighlight.matchesOrigin(
            true, 10, 64, -30, 10, 65, -30));
        Assert.assertFalse("Z 不同不匹配", ChainPreviewVanillaHighlight.matchesOrigin(
            true, 10, 64, -30, 10, 64, -29));
        Assert.assertTrue("负坐标同样支持", ChainPreviewVanillaHighlight.matchesOrigin(
            true, -1, -64, -2, -1, -64, -2));
    }

    @Test
    public void matchAndGateComposeIntoFailOpenDecision() {
        // 瞄准空气 / 未命中方块：hasOrigin 为 false → 不抑制
        boolean originMatches = ChainPreviewVanillaHighlight.matchesOrigin(false, 0, 0, 0, 0, 0, 0);
        Assert.assertFalse(ChainPreviewVanillaHighlight.shouldSuppress(true, true, originMatches));

        // 正常命中预览 origin：抑制
        originMatches = ChainPreviewVanillaHighlight.matchesOrigin(true, 5, 70, 5, 5, 70, 5);
        Assert.assertTrue(ChainPreviewVanillaHighlight.shouldSuppress(true, true, originMatches));
    }
}
