package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

/**
 * T37 / B3.x 真描边计划契约：描边壳段的 plan 派生态（默认 false/0 = 逐字节等于升级前）、
 * 规范化（未开启 / 宽度<=0 / NaN / 超上限）、零分配复用，以及派生时其余字段原样保留。
 */
public class ChainPreviewOutlinePlanTest {

    @Test
    public void legacyElevenArgConstructionEqualsExplicitOutlineOff() {
        ChainPreviewDrawPlan.Visuals legacy = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF);
        ChainPreviewDrawPlan.Visuals explicit = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF,
            false, 0.0F);

        Assert.assertFalse("默认档不是描边壳", legacy.isOutlineShell());
        Assert.assertEquals(0.0F, legacy.getOutlineWidthPx(), 0.0F);
        Assert.assertEquals(explicit, legacy);
        Assert.assertEquals(explicit.hashCode(), legacy.hashCode());
        Assert.assertSame("本就是规范形态必须返回自身（零分配）", legacy, legacy.sanitized());
    }

    @Test
    public void outlineDerivationKeepsEveryOtherVisualField() {
        ChainPreviewDrawPlan.Visuals base = ChainPreviewDrawPlan.Visuals.BASELINE;

        ChainPreviewDrawPlan.Visuals shell = base.withOutlinePass(true, 1.5F);

        Assert.assertNotSame(base, shell);
        Assert.assertTrue(shell.isOutlineShell());
        Assert.assertEquals(1.5F, shell.getOutlineWidthPx(), 0.0F);
        Assert.assertEquals(base.getBarThickness(), shell.getBarThickness(), 0.0F);
        Assert.assertEquals(base.getMinScreenWidthPx(), shell.getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals(base.getAnimationU(), shell.getAnimationU(), 0.0F);
        Assert.assertEquals(base.getFadeAlpha(), shell.getFadeAlpha(), 0.0F);
        Assert.assertEquals(base.getDepthChannel(), shell.getDepthChannel());
        Assert.assertEquals(base.getColors(), shell.getColors());
        Assert.assertEquals(base.getLod(), shell.getLod());
        Assert.assertNotEquals(base, shell);
    }

    @Test
    public void outlineCanonicalizationDegradesEveryInvalidForm() {
        ChainPreviewDrawPlan.Visuals base = ChainPreviewDrawPlan.Visuals.BASELINE;

        Assert.assertFalse("未开启 → 不算壳段且宽度归零",
            base.withOutlinePass(false, 1.5F).isOutlineShell());
        Assert.assertEquals(0.0F, base.withOutlinePass(false, 1.5F).getOutlineWidthPx(), 0.0F);
        Assert.assertFalse(base.withOutlinePass(true, 0.0F).isOutlineShell());
        Assert.assertFalse(base.withOutlinePass(true, -2.0F).isOutlineShell());
        Assert.assertFalse("NaN → 不算壳段", base.withOutlinePass(true, Float.NaN).isOutlineShell());
        Assert.assertFalse(
            base.withOutlinePass(true, Float.POSITIVE_INFINITY).isOutlineShell());

        ChainPreviewDrawPlan.Visuals clamped = base.withOutlinePass(true, 99.0F);
        Assert.assertTrue(clamped.isOutlineShell());
        Assert.assertEquals(
            ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX, clamped.getOutlineWidthPx(), 0.0F);

        Assert.assertSame("形态未变化必须返回自身（零分配）", base, base.withOutlinePass(false, 1.0F));
        Assert.assertSame(clamped, clamped.withOutlinePass(true, 99.0F));
        ChainPreviewDrawPlan.Visuals roundTrip =
            base.withOutlinePass(true, 1.5F).withOutlinePass(false, 0.0F);
        Assert.assertEquals("取消描边必须回到默认形态", ChainPreviewDrawPlan.Visuals.BASELINE, roundTrip);
        Assert.assertFalse(roundTrip.isOutlineShell());
    }

    @Test
    public void malformedOutlineInputsAreNarrowedBySanitize() {
        ChainPreviewDrawPlan.Visuals malformed = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.OUTLINE, 1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF,
            true, Float.NaN);
        ChainPreviewDrawPlan.Visuals safe = malformed.sanitized();
        Assert.assertFalse("NaN 宽度必须收敛为非壳段", safe.isOutlineShell());
        Assert.assertEquals(0.0F, safe.getOutlineWidthPx(), 0.0F);

        ChainPreviewDrawPlan.Visuals oversized = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.OUTLINE, 1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF,
            true, 99.0F).sanitized();
        Assert.assertTrue(oversized.isOutlineShell());
        Assert.assertEquals(
            ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX, oversized.getOutlineWidthPx(), 0.0F);
    }

    @Test
    public void planLevelDerivationPreservesRangeOriginMaskAndCounters() {
        ChainPreviewDrawPlan base = new ChainPreviewDrawPlan(
            0,
            24,
            null,
            ChainPreviewDrawPlan.Visuals.BASELINE,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
            10,
            64,
            -3,
            8,
            false,
            2,
            5L,
            7L).sanitized();

        ChainPreviewDrawPlan shell = base.withOutlinePass(true, 1.5F);

        Assert.assertNotSame(base, shell);
        Assert.assertTrue(shell.isOutlineShell());
        Assert.assertEquals(1.5F, shell.getOutlineWidthPx(), 0.0F);
        Assert.assertFalse("原计划不得被就地修改", base.isOutlineShell());
        Assert.assertEquals(base.getIndexOffset(), shell.getIndexOffset());
        Assert.assertEquals(base.getIndexCount(), shell.getIndexCount());
        Assert.assertEquals(base.getOriginX(), shell.getOriginX());
        Assert.assertEquals(base.getOriginY(), shell.getOriginY());
        Assert.assertEquals(base.getOriginZ(), shell.getOriginZ());
        Assert.assertEquals(base.getSemanticMask(), shell.getSemanticMask());
        Assert.assertEquals(base.getRebuilds(), shell.getRebuilds());
        Assert.assertEquals(base.getUploads(), shell.getUploads());
        Assert.assertEquals(base.getCulledTargetCount(), shell.getCulledTargetCount());
        Assert.assertEquals(base.getDepthChannel(), shell.getDepthChannel());
        Assert.assertNotEquals(base, shell);

        Assert.assertSame("未要求描边时返回自身（零分配）", base, base.withOutlinePass(false, 0.0F));
        Assert.assertSame(base, base.withOutlinePass(true, 0.0F));
        Assert.assertSame(shell, shell.withOutlinePass(true, 1.5F));
        Assert.assertFalse(shell.withOutlinePass(false, 0.0F).isOutlineShell());
    }
}
