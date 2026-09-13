package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewVanillaHighlight;

/**
 * T35 波次 7 原版高亮协同契约（B2.5 / task-34）。
 *
 * <p>判据独立于 owner 探针：真值表按规格自行枚举（8 组 = 开关 × 激活 × 匹配），坐标匹配另做
 * 边界与极值扫描；并断言 fail-open 不变量——只有 (true, true, true) 抑制，其余一律不抑制，
 * 且函数非常量（至少各存在一个 true / false，防止平凡通过）。</p>
 */
public class VanillaHighlightCoordinationContractTest {

    @Test
    public void suppressionTruthTableHasExactlyOneTrueRow() {
        int trueRows = 0;
        for (int mask = 0; mask < 8; mask++) {
            boolean configured = (mask & 4) != 0;
            boolean active = (mask & 2) != 0;
            boolean matched = (mask & 1) != 0;
            boolean suppress = ChainPreviewVanillaHighlight.shouldSuppress(configured, active, matched);
            boolean expected = configured && active && matched;
            Assert.assertEquals("行 mask=" + mask + "（开关=" + configured + ", 激活=" + active
                + ", 匹配=" + matched + "）抑制判定错误", expected, suppress);
            if (suppress) {
                trueRows++;
            }
        }
        Assert.assertEquals("8 组中必须恰好 1 组抑制（非常量判定）", 1, trueRows);
    }

    @Test
    public void requiredFourCasesFollowFailOpenOrder() {
        // 关闭档：即使激活且命中 origin 也不得抑制（默认观感逐字不变）
        Assert.assertFalse("关闭档不得抑制",
            ChainPreviewVanillaHighlight.shouldSuppress(false, true, true));
        // 激活但瞄准的不是 origin
        Assert.assertFalse("激活但目标不匹配不得抑制",
            ChainPreviewVanillaHighlight.shouldSuppress(true, true, false));
        // 预览结束（未激活）
        Assert.assertFalse("预览结束不得抑制",
            ChainPreviewVanillaHighlight.shouldSuppress(true, false, true));
        // 瞄准空气：没有 origin / 未命中 -> matchesOrigin 必须返回 false，组合后不抑制
        boolean aimedAir = ChainPreviewVanillaHighlight.matchesOrigin(
            false, 10, 64, -3, 0, 0, 0);
        Assert.assertFalse("瞄准空气必须判为不匹配", aimedAir);
        Assert.assertFalse("瞄准空气不得抑制",
            ChainPreviewVanillaHighlight.shouldSuppress(true, true, aimedAir));
    }

    @Test
    public void originMatchingCoversAxisBoundariesAndExtremes() {
        Assert.assertTrue("三轴全等必须匹配",
            ChainPreviewVanillaHighlight.matchesOrigin(true, 3, 64, -7, 3, 64, -7));
        Assert.assertFalse("无 origin 一律不匹配",
            ChainPreviewVanillaHighlight.matchesOrigin(false, 3, 64, -7, 3, 64, -7));
        Assert.assertFalse("X 差 1 不得匹配",
            ChainPreviewVanillaHighlight.matchesOrigin(true, 3, 64, -7, 4, 64, -7));
        Assert.assertFalse("Y 差 1 不得匹配",
            ChainPreviewVanillaHighlight.matchesOrigin(true, 3, 64, -7, 3, 65, -7));
        Assert.assertFalse("Z 差 1 不得匹配",
            ChainPreviewVanillaHighlight.matchesOrigin(true, 3, 64, -7, 3, 64, -6));
        Assert.assertTrue("极值相等必须匹配（无溢出假阴性）",
            ChainPreviewVanillaHighlight.matchesOrigin(true,
                Integer.MIN_VALUE, Integer.MAX_VALUE, 0,
                Integer.MIN_VALUE, Integer.MAX_VALUE, 0));
        Assert.assertFalse("极值不等不得匹配",
            ChainPreviewVanillaHighlight.matchesOrigin(true,
                Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
        Assert.assertTrue("负坐标必须按数值比较",
            ChainPreviewVanillaHighlight.matchesOrigin(true, -1, -64, -1, -1, -64, -1));
    }

    @Test
    public void suppressionIsMonotoneAndCompositionConsistent() {
        // 单调性：固定匹配=true 时，激活/开关打开不得使抑制结果回退
        boolean previous = false;
        for (int mask = 0; mask < 4; mask++) {
            boolean configured = (mask & 2) != 0;
            boolean active = (mask & 1) != 0;
            boolean suppress = ChainPreviewVanillaHighlight.shouldSuppress(configured, active, true);
            Assert.assertTrue("开关/激活打开后不得回退: mask=" + mask, suppress || !previous);
            previous = suppress;
        }
        Assert.assertTrue("扫描必须至少触发一次抑制（防止平凡通过）", previous);

        // 组合一致性：suppress 必须等价于三条件合取（含 matchesOrigin 结果）
        for (int mask = 0; mask < 8; mask++) {
            boolean configured = (mask & 4) != 0;
            boolean active = (mask & 2) != 0;
            boolean hasOrigin = (mask & 1) != 0;
            boolean matched = ChainPreviewVanillaHighlight.matchesOrigin(
                hasOrigin, 5, 5, 5, 5, 5, 5);
            Assert.assertEquals("组合必须等价于三条件合取: mask=" + mask,
                configured && active && matched,
                ChainPreviewVanillaHighlight.shouldSuppress(configured, active, matched));
        }
    }
}
