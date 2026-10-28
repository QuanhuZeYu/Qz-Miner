package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

/**
 * 以 CPU 参考模型和后端 uniform 数值验证描边像素换算、关闭恒等与共享世界预算。
 * 最小宽度优先消耗预算，描边仅取剩余额度；GLSL 位移和像素另经实机确认。
 */
public class ChainPreviewShaderOutlineTest {

    /** plan 契约里的默认描边宽度（1.5 物理像素，Lead 批准，不新增配置键）。 */
    private static final float DEFAULT_OUTLINE_PX = ChainPreviewDrawPlan.OUTLINE_WIDTH_DEFAULT_PX;

    /** 默认条柱厚度（与 ChainPreviewMeshBuilder 的默认一致；决定描边世界上界）。 */
    private static final float DEFAULT_BAR_THICKNESS = 0.045F;

    // ------------------------------------------------------------------ 三档等价回归

    /** 关闭描边（xray / occlude / 主体 pass）时外扩量必须精确为 0 ⇒ 位移矩阵恒等。 */
    @Test
    public void disabledOutlineProducesExactlyZeroWidening() {
        float[] pixelsPerUnitSamples = {0.001F, 0.1F, 1.0F, 10.0F, 900.0F, 10000.0F};
        for (float pixelsPerUnit : pixelsPerUnitSamples) {
            Assert.assertEquals("widthPx=0 必须精确不外扩（pixelsPerUnit=" + pixelsPerUnit + "）",
                    0.0F, ChainPreviewShaderMath.outlineWidenWorld(
                            0.0F, pixelsPerUnit, DEFAULT_BAR_THICKNESS), 0.0F);
            Assert.assertFalse("widthPx=0 必须判定为关闭",
                    ChainPreviewShaderMath.isOutlineEnabled(0.0F));
        }
    }

    // ------------------------------------------------------------------ 壳段外扩量映射

    /** px → world 换算必须与 minScreenWidth 同口径（1 世界单位 = pixelsPerWorldUnit 像素）。 */
    @Test
    public void shellWideningConvertsPixelsToWorld() {
        float thickness = DEFAULT_BAR_THICKNESS;
        Assert.assertEquals("1.5px @ 900px/单位 = 1.5/900 世界",
                DEFAULT_OUTLINE_PX / 900.0F,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 900.0F, thickness), 1.0e-7F);
        // 采样必须避开世界上限（默认厚度下为 0.455）：1.5px @ 10px/单位 = 0.15 世界，未触顶
        Assert.assertEquals("1.5px @ 10px/单位 = 0.15 世界",
                DEFAULT_OUTLINE_PX / 10.0F,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 10.0F, thickness), 1.0e-7F);
        // 单调：像素/单位越大（越远），同一像素宽度对应越小的世界外扩
        float near = ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 10.0F, thickness);
        float far = ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 1000.0F, thickness);
        Assert.assertTrue("越远外扩越小", far < near);
    }

    // ------------------------------------------------------------------ 宽度边界收敛

    /** 宽度 0 / 负 / NaN 一律收敛为关闭（精确 0）。 */
    @Test
    public void nonPositiveWidthCollapsesToDisabled() {
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(0.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(-1.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(-999.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(Float.NaN), 0.0F);
        Assert.assertFalse(ChainPreviewShaderMath.isOutlineEnabled(-2.0F));
        Assert.assertEquals("负宽度外扩量必须为 0",
                0.0F, ChainPreviewShaderMath.outlineWidenWorld(-5.0F, 900.0F, DEFAULT_BAR_THICKNESS), 0.0F);
    }

    // ------------------------------------------------------------------ F-1：外扩上界与相邻不重叠

    /**
     * F-1（cross-review 取证）：世界上界必须是 {@code 0.5 − barThickness}，不是固定 0.5。
     *
     * <p>固定 0.5 在默认厚度 0.045 下会让相邻条柱重叠 0.09 格（中心距 1 格、两侧同时外扩）。</p>
     */
    @Test
    public void widenCapSubtractsBarThickness() {
        Assert.assertEquals("厚度 0.045 ⇒ 上界 0.455",
                0.455F, ChainPreviewShaderMath.maxWidenWorld(0.045F), 1.0e-7F);
        Assert.assertEquals("厚度 0.2 ⇒ 上界 0.3",
                0.3F, ChainPreviewShaderMath.maxWidenWorld(0.2F), 1.0e-7F);
        Assert.assertEquals("厚度 0.005（下限）⇒ 上界 0.495",
                0.495F, ChainPreviewShaderMath.maxWidenWorld(0.005F), 1.0e-7F);
        Assert.assertEquals("厚度 >= 0.5（极端）⇒ 上界收敛 0",
                0.0F, ChainPreviewShaderMath.maxWidenWorld(0.5F), 0.0F);
        Assert.assertEquals("厚度 0.99 ⇒ 仍为 0（不得为负）",
                0.0F, ChainPreviewShaderMath.maxWidenWorld(0.99F), 0.0F);
        Assert.assertEquals("NaN 厚度 ⇒ 最保守 0",
                0.0F, ChainPreviewShaderMath.maxWidenWorld(Float.NaN), 0.0F);
    }

    /** 上界必须真的作用在换算上：请求超大宽度 ⇒ 恰好落在 0.455（默认厚度）。 */
    @Test
    public void widenWorldSaturatesAtThicknessDependentCap() {
        float thickness = DEFAULT_BAR_THICKNESS;
        float saturated = ChainPreviewShaderMath.outlineWidenWorld(1.0e6F, 0.0001F, thickness);
        Assert.assertEquals("巨大请求必须收敛到上界", 0.455F, saturated, 1.0e-6F);
        Assert.assertFalse("不得为 NaN", Float.isNaN(saturated));
    }

    /** F-1 的验收核心：默认厚度 + 最大外扩时，相邻条柱（中心距 1 格）不得重叠。 */
    @Test
    public void neighboursDoNotOverlapAtMaximumWidening() {
        for (float thickness : new float[] {0.005F, 0.045F, 0.1F, 0.2F}) {
            float cap = ChainPreviewShaderMath.maxWidenWorld(thickness);
            // 请求远超上界 ⇒ 实际取到上界
            float widen = ChainPreviewShaderMath.outlineWidenWorld(1.0e6F, 0.0001F, thickness);
            Assert.assertEquals("厚度 " + thickness + " 时必须取到上界", cap, widen, 1.0e-6F);

            float gap = ChainPreviewShaderMath.neighbourGap(thickness, widen);
            Assert.assertTrue("厚度 " + thickness + " 在上界处不得重叠，实际间隙=" + gap, gap >= -1.0e-6F);
        }

        // 反证：旧口径（固定 0.5）在默认厚度下确实重叠
        float legacyGap = ChainPreviewShaderMath.neighbourGap(DEFAULT_BAR_THICKNESS, 0.5F);
        Assert.assertEquals("旧固定 0.5 的间隙应为 -0.09（重叠）", -0.09F, legacyGap, 1.0e-6F);
        Assert.assertTrue("旧口径确实重叠", legacyGap < 0.0F);
    }

    // ------------------------------------------------------------------ 联合上界（min-width 优先，描边让位）

    /**
     * T52 联合上界：最小宽度与真描边<strong>共用同一份</strong>世界空间预算
     * （{@link ChainPreviewShaderMath#maxWidenWorld(float)}），故联合位移恒 {@code <=} 该上界。
     *
     * <p>修复前两项各自取上界，联合可达 {@code 2×(0.5 − t)}：t=0.045 时单侧位移 0.91 格、
     * 到达半径 0.9325 格 ⇒ 越出自身方块（半宽 0.5）且相邻条柱重叠。本测试在
     * 「厚度 × 像素密度 × 最小宽度 × 描边宽度」网格上逐格钉住新性质（Python 独立复算见工作站
     * {@code temp/qz-miner-t52-joint-budget.py}：旧行为 4 个厚度全部越界，新行为 420 格零违规）。</p>
     */
    @Test
    public void combinedDisplacementNeverExceedsTheSharedWorldBudget() {
        int cells = 0;
        float worstExcess = 0.0F;
        for (float thickness : new float[] {0.005F, 0.045F, 0.1F, 0.2F, 0.5F}) {
            float cap = ChainPreviewShaderMath.maxWidenWorld(thickness);
            for (float pixelsPerWorldUnit : new float[] {1.0e-4F, 0.001F, 0.01F, 0.05F, 1.0F, 24.0F, 900.0F}) {
                for (float minWidthPx : new float[] {0.0F, 1.0F, 4.0F, 8.0F}) {
                    for (float outlinePx : new float[] {0.0F, DEFAULT_OUTLINE_PX, 8.0F}) {
                        float[] after = ChainPreviewShaderMath.displaceVertex(
                                0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F,
                                pixelsPerWorldUnit, minWidthPx, thickness, outlinePx);
                        float widen = after[1];
                        worstExcess = Math.max(worstExcess, widen - cap);
                        String label = "（t=" + thickness + " ppwu=" + pixelsPerWorldUnit
                                + " minW=" + minWidthPx + " outline=" + outlinePx
                                + " 位移=" + widen + " 上界=" + cap + "）";
                        Assert.assertTrue("联合位移不得超出共用预算" + label, widen <= cap + 1.0e-6F);
                        Assert.assertTrue("位移不得为负" + label, widen >= 0.0F);
                        Assert.assertTrue("到达半径不得越出自身方块" + label,
                                thickness * 0.5F + widen <= 0.5F + 1.0e-6F);
                        Assert.assertTrue("相邻条柱（中心距 1 格）不得重叠" + label,
                                ChainPreviewShaderMath.neighbourGap(thickness, widen) >= -1.0e-6F);
                        cells++;
                    }
                }
            }
        }
        Assert.assertEquals("网格格数（5 厚度 × 7 像素密度 × 4 最小宽度 × 3 描边宽度）", 420, cells);
        Assert.assertTrue("最大超出量必须为 0，实际 " + worstExcess, worstExcess <= 1.0e-6F);
    }

    /** 最小宽度占满共享预算时，描边位移精确归零。 */
    @Test
    public void outlineYieldsToMinWidthWhenBudgetIsExhausted() {
        float thickness = DEFAULT_BAR_THICKNESS;
        float cap = ChainPreviewShaderMath.maxWidenWorld(thickness);
        float pixelsPerWorldUnit = 1.0e-4F;
        Assert.assertEquals("测试前提：最小宽度单独就已吃满预算", cap,
                ChainPreviewShaderMath.minWidthWidenWorld(8.0F, thickness, pixelsPerWorldUnit), 1.0e-6F);
        Assert.assertEquals("测试前提：描边单独同样会吃满预算", cap,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, pixelsPerWorldUnit, thickness), 1.0e-6F);
        Assert.assertEquals("预算耗尽时描边的实际外扩量必须是精确 0",
                0.0F, ChainPreviewShaderMath.outlineWidenWithinBudget(
                        DEFAULT_OUTLINE_PX, pixelsPerWorldUnit, thickness, cap), 0.0F);

        float[] after = ChainPreviewShaderMath.displaceVertex(
                0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F,
                pixelsPerWorldUnit, 8.0F, thickness, DEFAULT_OUTLINE_PX);
        Assert.assertEquals("联合位移必须等于共用预算（而不是 2 × 预算）", cap, after[1], 1.0e-6F);
        Assert.assertFalse("不得为 NaN", Float.isNaN(after[1]));
        Assert.assertTrue("到达半径不得越出自身方块",
                thickness * 0.5F + after[1] <= 0.5F + 1.0e-6F);
    }

    /**
     * 预算未耗尽时描边按剩余额度让位（不是一律取消），且最小宽度仍被完整交付（功能性优先）。
     *
     * <p>参数（t=0.045、ppwu=10、minW=8、描边=1.5px）经 Python 复算：最小宽度位移 0.3775、
     * 剩余额度 0.0775，而描边请求 0.15 ⇒ 必须被压到 0.0775（既不为 0，也不再是它自己那份上界）。</p>
     */
    @Test
    public void outlineUsesOnlyRemainingBudgetWhenMinWidthIsActive() {
        float thickness = DEFAULT_BAR_THICKNESS;
        float cap = ChainPreviewShaderMath.maxWidenWorld(thickness);
        float pixelsPerWorldUnit = 10.0F;
        float minWidthPx = 8.0F;

        float minWidthWiden = ChainPreviewShaderMath.minWidthWidenWorld(
                minWidthPx, thickness, pixelsPerWorldUnit);
        float requestedOutline = DEFAULT_OUTLINE_PX / pixelsPerWorldUnit;
        float remaining = Math.max(0.0F, cap - minWidthWiden); // 独立复算，不调实现
        Assert.assertTrue("测试前提：预算未耗尽，剩余额度 " + remaining, remaining > 0.0F);
        Assert.assertTrue("测试前提：描边请求必须超出剩余额度，否则测不到让位（请求 "
                + requestedOutline + " / 剩余 " + remaining + "）", requestedOutline > remaining);

        float[] after = ChainPreviewShaderMath.displaceVertex(
                0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 0.0F,
                pixelsPerWorldUnit, minWidthPx, thickness, DEFAULT_OUTLINE_PX);
        float outlineWiden = after[1] - minWidthWiden;
        Assert.assertTrue("描边必须让位但不得被取消（实际 " + outlineWiden + "）", outlineWiden > 0.0F);
        Assert.assertEquals("描边只能用到剩余额度", remaining, outlineWiden, 1.0e-6F);
        Assert.assertEquals("联合位移恰好吃满预算（未越界）", cap, after[1], 1.0e-6F);
        Assert.assertTrue("最小宽度的交付量不得被描边削减：(t + 2d) × ppwu = "
                        + (thickness + 2.0F * minWidthWiden) * pixelsPerWorldUnit,
                (thickness + 2.0F * minWidthWiden) * pixelsPerWorldUnit >= minWidthPx - 1.0e-3F);
    }

    // ------------------------------------------------------------------ 宽度上限与常量单一真源

    /** 超限宽度必须收敛到上限（像素侧 8px；世界侧 0.5 格）。 */
    @Test
    public void oversizedWidthIsClampedOnBothSides() {
        Assert.assertEquals("像素侧收敛到 8",
                ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderMath.outlineWidthPx(1000.0F), 0.0F);
        Assert.assertEquals("刚好等于上限时保持",
                ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderMath.outlineWidthPx(ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX), 0.0F);

        // 极近视角：1px 对应很大世界量 ⇒ 世界侧必须被「0.5 − 厚度」封顶（F-1）
        Assert.assertEquals("世界侧收敛到 0.5 − barThickness",
                ChainPreviewShaderMath.maxWidenWorld(DEFAULT_BAR_THICKNESS),
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 0.0001F, DEFAULT_BAR_THICKNESS), 0.0F);

        // 非法 pixelsPerWorldUnit 不得产生 NaN / 无穷
        float thickness = DEFAULT_BAR_THICKNESS;
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 0.0F, thickness), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, -1.0F, thickness), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, Float.NaN, thickness), 0.0F);
        Assert.assertFalse("不得产生 NaN",
                Float.isNaN(ChainPreviewShaderMath.outlineWidenWorld(
                        DEFAULT_OUTLINE_PX, Float.POSITIVE_INFINITY, thickness)));
    }

    /**
     * 后端把 plan 的描边面映射成 uniform 取值：只有壳段非 0，且宽度经 host 侧收敛。
     *
     * <p>断言打在 {@link ChainPreviewShaderBackend#outlineWidthFor(ChainPreviewDrawPlan)} 的<b>数值</b>
     * 上——「给定 plan 产出什么 uniform 值」才是接线契约；「源码里有没有写
     * {@code plan.isOutlineShell() ? ... : 0.0F}」重命名即误报、把三元改成恒 0 却照样绿。</p>
     */
    @Test
    public void outlineWidthUniformFollowsShellFlagAndHostClamp() {
        ChainPreviewDrawPlan shell = planWithVisuals(
                ChainPreviewDrawPlan.Visuals.BASELINE.withOutlinePass(true, DEFAULT_OUTLINE_PX));
        Assert.assertTrue("测试前提：必须是描边壳段", shell.isOutlineShell());
        Assert.assertEquals("壳段宽度必须原样进 uniform", DEFAULT_OUTLINE_PX,
                ChainPreviewShaderBackend.outlineWidthFor(shell), 0.0F);

        ChainPreviewDrawPlan legacy = planWithVisuals(ChainPreviewDrawPlan.Visuals.BASELINE);
        Assert.assertFalse("测试前提：默认档不是壳段", legacy.isOutlineShell());
        Assert.assertEquals("非壳段（xray / occlude / 主体段）必须精确为 0 ⇒ 位移矩阵恒等",
                0.0F, ChainPreviewShaderBackend.outlineWidthFor(legacy), 0.0F);

        // 未收敛的壳段（raw 构造绕过 withOutlinePass / sanitize 的收窄口径）：host 侧必须收敛
        Assert.assertEquals("超限宽度必须收敛到上限", ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderBackend.outlineWidthFor(rawShellPlan(1000.0F)), 0.0F);
        Assert.assertEquals("非有限宽度必须收敛到上限", ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderBackend.outlineWidthFor(rawShellPlan(Float.POSITIVE_INFINITY)), 0.0F);
    }

    /** 非壳段 / 默认档 plan（只读视觉参数，其余字段不参与本组断言）。 */
    private static ChainPreviewDrawPlan planWithVisuals(ChainPreviewDrawPlan.Visuals visuals) {
        return new ChainPreviewDrawPlan(
                0, 24, null, visuals, ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
                0, 0, 0, 8, false, 0, 0L, 0L);
    }

    /**
     * 绕开 {@code withOutlinePass} / {@code sanitized()} 收窄口径的畸形壳段（raw 构造）。
     *
     * <p>用途：证明「收敛」这一步真的发生在后端取值路径上，而不是只依赖 plan 侧规范化——
     * 生产入口是 {@code derive}（内部已 sanitize），这里刻意构造它拦不住的输入。</p>
     */
    private static ChainPreviewDrawPlan rawShellPlan(float widthPx) {
        return planWithVisuals(new ChainPreviewDrawPlan.Visuals(
                0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
                ChainPreviewDrawPlan.DepthChannel.OUTLINE, 1.0F,
                ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF,
                true, widthPx));
    }

    /** 常量单一真源：参考模型的像素上限必须引用 plan，而不是自己写一份。 */
    @Test
    public void outlineWidthCapHasSingleSourceOfTruth() {
        Assert.assertEquals("上限必须等于 plan 的契约值",
                ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX, 0.0F);
        Assert.assertEquals("plan 默认宽度必须是 1.5px", 1.5F,
                ChainPreviewDrawPlan.OUTLINE_WIDTH_DEFAULT_PX, 0.0F);
    }
}
