package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderMath;

/**
 * T39 波次 8 真描边契约（B3.x / task-37）：宽度收敛边界、段序派生、alpha 包络不变、
 * 以及独立复算的「相邻条柱两侧外扩后不得重叠」数值判据（F-1 边界）。
 */
public class OutlineStrokeBoundsContractTest {

    private static final float MAX_WIDTH = ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX;
    private static final float DEFAULT_WIDTH = ChainPreviewDrawPlan.OUTLINE_WIDTH_DEFAULT_PX;

    @Test
    public void widthClampBoundariesAreNarrowedNotPassedThrough() {
        Assert.assertEquals("负宽度必须收敛为 0", 0.0F, ChainPreviewShaderMath.outlineWidthPx(-1.0F), 0.0F);
        Assert.assertEquals("零宽度必须为 0", 0.0F, ChainPreviewShaderMath.outlineWidthPx(0.0F), 0.0F);
        Assert.assertEquals("NaN 必须收敛为 0", 0.0F, ChainPreviewShaderMath.outlineWidthPx(Float.NaN), 0.0F);
        Assert.assertEquals("默认宽度必须原值保留",
            DEFAULT_WIDTH, ChainPreviewShaderMath.outlineWidthPx(DEFAULT_WIDTH), 0.0F);
        Assert.assertEquals("上限必须原值保留",
            MAX_WIDTH, ChainPreviewShaderMath.outlineWidthPx(MAX_WIDTH), 0.0F);
        Assert.assertEquals("超限必须收敛到上限",
            MAX_WIDTH, ChainPreviewShaderMath.outlineWidthPx(MAX_WIDTH * 4.0F), 0.0F);
        Assert.assertEquals("+Inf 必须收敛到上限",
            MAX_WIDTH, ChainPreviewShaderMath.outlineWidthPx(Float.POSITIVE_INFINITY), 0.0F);

        Assert.assertFalse(ChainPreviewShaderMath.isOutlineEnabled(-1.0F));
        Assert.assertFalse(ChainPreviewShaderMath.isOutlineEnabled(0.0F));
        Assert.assertFalse(ChainPreviewShaderMath.isOutlineEnabled(Float.NaN));
        Assert.assertTrue(ChainPreviewShaderMath.isOutlineEnabled(DEFAULT_WIDTH));
    }

    @Test
    public void widenIsExactlyZeroWhenDisabledOrDegenerate() {
        Assert.assertEquals("宽度 0 必须精确外扩 0（默认档逐值不变）",
            0.0F, ChainPreviewShaderMath.outlineWidenWorld(0.0F, 128.0F), 0.0F);
        Assert.assertEquals("负宽度必须精确外扩 0",
            0.0F, ChainPreviewShaderMath.outlineWidenWorld(-2.0F, 128.0F), 0.0F);
        Assert.assertEquals("NaN 宽度必须精确外扩 0",
            0.0F, ChainPreviewShaderMath.outlineWidenWorld(Float.NaN, 128.0F), 0.0F);
        Assert.assertEquals("非正像素密度必须精确外扩 0",
            0.0F, ChainPreviewShaderMath.outlineWidenWorld(MAX_WIDTH, 0.0F), 0.0F);
        Assert.assertEquals("NaN 像素密度必须精确外扩 0",
            0.0F, ChainPreviewShaderMath.outlineWidenWorld(MAX_WIDTH, Float.NaN), 0.0F);
        Assert.assertEquals("无限像素密度必须精确外扩 0",
            0.0F, ChainPreviewShaderMath.outlineWidenWorld(MAX_WIDTH, Float.POSITIVE_INFINITY), 0.0F);
        Assert.assertTrue("正常参数必须产生正外扩",
            ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_WIDTH, 4.0F) > 0.0F);
    }

    /**
     * F-1 独立复算：相邻条柱（中心相距 1 格、厚度 t）两侧同时外扩 w 后不得重叠，
     * 且单侧外扩不得让条柱越出自身方块。
     *
     * <p>判据：{@code 1 - t - 2w >= 0}（间隙非负）且 {@code t/2 + w <= 0.5}（不越界）。
     * 取饱和像素密度让外扩量顶到实现上界。</p>
     */
    @Test
    public void expandedNeighborBarsMustNotOverlapForAnyThickness() {
        float[] thicknesses = {0.045F, 0.2F, 0.5F};
        StringBuilder detail = new StringBuilder();
        for (float thickness : thicknesses) {
            float widen = widenFor(MAX_WIDTH, 1.0F, thickness);
            float gap = 1.0F - thickness - 2.0F * widen;
            float reach = thickness * 0.5F + widen;
            detail.append("[t=").append(thickness).append(" widen=").append(widen)
                .append(" gap=").append(gap).append(" reach=").append(reach).append("] ");
            Assert.assertTrue("相邻条柱外扩后不得重叠（F-1）: " + detail, gap >= 0.0F);
            Assert.assertTrue("外扩后不得越出自身方块: " + detail, reach <= 0.5F + 1.0E-4F);
            // Lead 裁定的参数化上界：max(0, 0.5 - barThickness)
            float rulingBound = Math.max(0.0F, 0.5F - thickness);
            Assert.assertTrue("外扩上界必须收敛到 0.5 - barThickness: " + detail,
                widen <= rulingBound + 1.0E-4F);
        }
        Assert.assertEquals("厚度 0.045 对应上界 0.455", 0.455F,
            widenFor(MAX_WIDTH, 1.0F, 0.045F), 1.0E-3F);
        Assert.assertEquals("厚度 0.2 对应上界 0.3", 0.3F,
            widenFor(MAX_WIDTH, 1.0F, 0.2F), 1.0E-3F);
        Assert.assertEquals("厚度 >= 0.5 必须禁用外扩", 0.0F,
            widenFor(MAX_WIDTH, 1.0F, 0.5F), 0.0F);
        Assert.assertEquals("厚度超 0.5 同样禁用外扩", 0.0F,
            widenFor(MAX_WIDTH, 1.0F, 0.9F), 0.0F);
        Assert.assertEquals("默认厚度下外扩仍受像素密度收敛",
            1.5F / 64.0F, widenFor(1.5F, 64.0F, 0.045F), 1.0E-5F);
        Assert.assertTrue("参数化上界必须可用（0.5 - barThickness）: " + detail,
            hasParameterizedWiden() || widenFor(MAX_WIDTH, 1.0F, 0.045F) <= 0.5F - 0.045F + 1.0E-4F);
    }

    /**
     * 外扩量求值：优先使用带厚度的参数化上界（F-1 修复契约：{@code 0.5 - barThickness}）；
     * 尚未参数化时回退两参形式（其固定上界对厚条柱仍然重叠 → 该行会红，正是 F-1 判据）。
     */
    private static float widenFor(float widthPx, float pixelsPerWorldUnit, float barThickness) {
        try {
            java.lang.reflect.Method method = ChainPreviewShaderMath.class.getMethod(
                "outlineWidenWorld", float.class, float.class, float.class);
            return ((Float) method.invoke(null, Float.valueOf(widthPx),
                Float.valueOf(pixelsPerWorldUnit), Float.valueOf(barThickness))).floatValue();
        } catch (NoSuchMethodException missing) {
            return ChainPreviewShaderMath.outlineWidenWorld(widthPx, pixelsPerWorldUnit);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("参数化外扩量调用失败", failure);
        }
    }

    private static boolean hasParameterizedWiden() {
        try {
            ChainPreviewShaderMath.class.getMethod(
                "outlineWidenWorld", float.class, float.class, float.class);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    @Test
    public void outlinePassNormalizationTurnsInvalidShellIntoPlainSegment() {
        ChainPreviewDrawPlan.Visuals baseline = ChainPreviewDrawPlan.Visuals.BASELINE;
        Assert.assertFalse("默认档不得是描边壳", baseline.isOutlineShell());
        Assert.assertEquals("默认档描边宽度必须为 0", 0.0F, baseline.getOutlineWidthPx(), 0.0F);
        Assert.assertFalse("默认档描边必须判为关闭",
            ChainPreviewShaderMath.isOutlineEnabled(baseline.getOutlineWidthPx()));

        Assert.assertFalse("宽度 <=0 时壳标志必须收敛为 false",
            baseline.withOutlinePass(true, -1.0F).isOutlineShell());
        Assert.assertEquals(0.0F, baseline.withOutlinePass(true, -1.0F).getOutlineWidthPx(), 0.0F);
        Assert.assertFalse("宽度 0 时壳标志必须收敛为 false",
            baseline.withOutlinePass(true, 0.0F).isOutlineShell());
        Assert.assertFalse("非壳段即使给宽度也必须收敛为普通段",
            baseline.withOutlinePass(false, DEFAULT_WIDTH).isOutlineShell());
        Assert.assertEquals("非壳段宽度必须为 0",
            0.0F, baseline.withOutlinePass(false, DEFAULT_WIDTH).getOutlineWidthPx(), 0.0F);

        ChainPreviewDrawPlan.Visuals shell = baseline.withOutlinePass(true, DEFAULT_WIDTH);
        Assert.assertTrue(shell.isOutlineShell());
        Assert.assertEquals(DEFAULT_WIDTH, shell.getOutlineWidthPx(), 0.0F);

        ChainPreviewDrawPlan.Visuals clamped = baseline.withOutlinePass(true, MAX_WIDTH * 3.0F);
        Assert.assertEquals("超限宽度必须收敛到上限", MAX_WIDTH, clamped.getOutlineWidthPx(), 0.0F);
        Assert.assertTrue(clamped.isOutlineShell());
    }

    @Test
    public void strokeOnlyChangesShellAndWidthKeepingAlphaEnvelopeIdentical() {
        ChainPreviewDrawPlan.Visuals base = ChainPreviewDrawPlan.Visuals.BASELINE;
        ChainPreviewDrawPlan.Visuals shell = base.withOutlinePass(true, DEFAULT_WIDTH);

        Assert.assertEquals("淡出起点半径不得被描边改变",
            base.getFadeStartRadius(), shell.getFadeStartRadius(), 0.0F);
        Assert.assertEquals("淡出终点半径不得被描边改变",
            base.getFadeEndRadius(), shell.getFadeEndRadius(), 0.0F);
        Assert.assertEquals("近端 alpha 不得被描边改变", base.getAlphaStart(), shell.getAlphaStart(), 0.0F);
        Assert.assertEquals("远端 alpha 不得被描边改变", base.getAlphaEnd(), shell.getAlphaEnd(), 0.0F);
        Assert.assertEquals("fade 乘子不得被描边改变", base.getFadeAlpha(), shell.getFadeAlpha(), 0.0F);
        Assert.assertEquals("动画进度不得被描边改变", base.getAnimationU(), shell.getAnimationU(), 0.0F);
        Assert.assertEquals("条柱厚度不得被描边改变", base.getBarThickness(), shell.getBarThickness(), 0.0F);
        Assert.assertEquals("最小屏幕宽度不得被描边改变",
            base.getMinScreenWidthPx(), shell.getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals("深度通道不得被描边改变", base.getDepthChannel(), shell.getDepthChannel());
        Assert.assertEquals("LOD 档位不得被描边改变", base.getLod(), shell.getLod());
        Assert.assertEquals("颜色来源不得被描边改变", base.getColors(), shell.getColors());
    }

    @Test
    public void shellStageIsFirstOutlineSegmentOnly() {
        Assert.assertTrue("OUTLINE 首段必须是描边壳",
            ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OUTLINE, 0));
        Assert.assertFalse("OUTLINE 主体段不得是壳",
            ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OUTLINE, 1));
        Assert.assertFalse("OUTLINE 越界索引不得是壳",
            ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OUTLINE, 5));
        Assert.assertFalse("XRAY 不得有壳段",
            ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.XRAY, 0));
        Assert.assertFalse("OCCLUDE 不得有壳段",
            ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OCCLUDE, 0));
        Assert.assertFalse("null 档不得有壳段",
            ChainPreviewDepthPass.isOutlineShellStage(null, 0));
    }
}
