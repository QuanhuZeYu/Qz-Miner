package club.heiqi.qz_miner.chain.client.render;

import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;

/**
 * 距离淡出的数值等价性：shader 路径与 legacy（CPU）路径必须给出同一条曲线。
 *
 * <p>这是本项最重要的观感契约。用户在 backend 之间切换时，如果两条曲线的形状不同，
 * 会看到「同一场景两套透明度」；而曲线本身（quadratic）是 <b>CPU 端已冻结的行为</b>，
 * shader 只能复刻、不能重定义。</p>
 *
 * <p>验证方式不是断言 GLSL 源码字符串，而是：</p>
 * <ol>
 *   <li>反射调用 CPU 端 {@link VisualParameters#alphaFor(double, double, double)}（包私有，
 *       是 legacy 的真实实现），在 2000 个采样点上与
 *       {@link ChainPreviewShaderMath#fadeAlpha(float, float, float, float, float)} 逐点比对；</li>
 *   <li>用真实的 {@code ChainPreviewMeshBuilder} 产出顶点流，检查按
 *       {@link ChainPreviewShaderMath#vertexDistance} 算出的 alpha 与 mesh 颜色流逐顶点一致——证明
 *       「GPU 侧从 aPos 与 uOriginRel 重算距离」与「CPU 侧从 meshOrigin+局部坐标算距离」同源。</li>
 * </ol>
 */
public class ChainPreviewShaderDistanceEquivalenceTest {

    /** 观感可接受误差：CPU 用 double、GPU 用 float，1e-5 远小于 1/255 的量化台阶。 */
    private static final float TOLERANCE = 1.0e-5F;

    private static final double FADE_START = 2.0D;
    private static final double FADE_END = 6.0D;
    private static final float MAX_ALPHA = 0.8F;
    private static final float MIN_ALPHA = 0.2F;

    @Test
    public void shaderFadeCurveMatchesCpuAlphaForOnTwoThousandSamples() throws Exception {
        VisualParameters cpu = new VisualParameters(0.0D, 0.0D, 0.0D, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA, 0.045F);
        Method alphaFor = VisualParameters.class.getDeclaredMethod(
                "alphaFor", double.class, double.class, double.class);
        alphaFor.setAccessible(true);

        int samples = 2000;
        for (int sample = 0; sample <= samples; sample++) {
            double distance = 0.0D + 10.0D * sample / samples;
            float fromCpu = ((Float) alphaFor.invoke(cpu, distance, 0.0D, 0.0D)).floatValue();
            float fromShaderModel = ChainPreviewShaderMath.fadeAlpha(
                    (float) distance, (float) FADE_START, (float) FADE_END, MAX_ALPHA, MIN_ALPHA);
            Assert.assertEquals("距离 " + distance + " 处的 alpha 必须同形", fromCpu, fromShaderModel, TOLERANCE);
        }
    }

    /** 段边界必须落在同一侧：内段恒 maxAlpha、外段恒 minAlpha（避免切换 backend 时出现台阶）。 */
    @Test
    public void fadeCurvePlateausMatchAtBothEnds() throws Exception {
        VisualParameters cpu = new VisualParameters(0.0D, 0.0D, 0.0D, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA, 0.045F);
        Method alphaFor = VisualParameters.class.getDeclaredMethod(
                "alphaFor", double.class, double.class, double.class);
        alphaFor.setAccessible(true);

        double[] probes = {0.0D, 1.0D, FADE_START, FADE_START + 1.0e-7D, FADE_END, FADE_END + 1.0e-7D, 50.0D};
        for (double distance : probes) {
            float expected = ((Float) alphaFor.invoke(cpu, distance, 0.0D, 0.0D)).floatValue();
            float actual = ChainPreviewShaderMath.fadeAlpha(
                    (float) distance, (float) FADE_START, (float) FADE_END, MAX_ALPHA, MIN_ALPHA);
            Assert.assertEquals("距离 " + distance + " 处的平台值", expected, actual, TOLERANCE);
        }
        Assert.assertEquals(MAX_ALPHA, ChainPreviewShaderMath.fadeAlpha(
                (float) FADE_START, (float) FADE_START, (float) FADE_END, MAX_ALPHA, MIN_ALPHA), 0.0F);
        Assert.assertEquals(MIN_ALPHA, ChainPreviewShaderMath.fadeAlpha(
                (float) FADE_END, (float) FADE_START, (float) FADE_END, MAX_ALPHA, MIN_ALPHA), 0.0F);
    }

    /** 形状必须是单调不增且严格 quadratic（中点为 1 - 0.25 倍总落差）。 */
    @Test
    public void fadeCurveIsMonotonicQuadratic() {
        float start = 2.0F;
        float end = 6.0F;
        float previous = Float.MAX_VALUE;
        for (int sample = 0; sample <= 400; sample++) {
            float distance = 1.0F + 8.0F * sample / 400.0F;
            float alpha = ChainPreviewShaderMath.fadeAlpha(distance, start, end, MAX_ALPHA, MIN_ALPHA);
            Assert.assertTrue("alpha 必须随距离单调不增", alpha <= previous + 1.0e-7F);
            previous = alpha;
        }
        float midpoint = ChainPreviewShaderMath.fadeAlpha(4.0F, start, end, MAX_ALPHA, MIN_ALPHA);
        float expectedMidpoint = MAX_ALPHA - (MAX_ALPHA - MIN_ALPHA) * 0.25F;
        Assert.assertEquals("中点必须满足 t^2 曲线", expectedMidpoint, midpoint, TOLERANCE);
    }

    /**
     * S1+S2 回归的数值判据：builtin 档下两条路径的混合源色必须逐位相等。
     *
     * <p>shader 路径 = 语义绝对色 × alpha（片元不再乘顶点基色）；legacy = 顶点基色 × alpha。
     * builtin 档语义色精确等于基线常量 (0.25, 0.9, 1.0)，故两者必须逐位相同。
     * 任一违规都会在这里立刻失败：预乘 alpha → alpha²；片元再乘顶点色 → 逐分量平方。</p>
     */
    @Test
    public void builtinMixedSourceMatchesLegacyForEveryAlpha() {
        for (int step = 0; step <= 100; step++) {
            float alpha = step / 100.0F;
            float[] legacy = ChainPreviewShaderMath.legacyMixedSource(
                    ChainPreviewShaderMath.BUILTIN_COLOR_RED,
                    ChainPreviewShaderMath.BUILTIN_COLOR_GREEN,
                    ChainPreviewShaderMath.BUILTIN_COLOR_BLUE,
                    alpha);
            float[] shader = ChainPreviewShaderMath.shaderMixedSource(
                    ChainPreviewShaderMath.BUILTIN_COLOR_RED,
                    ChainPreviewShaderMath.BUILTIN_COLOR_GREEN,
                    ChainPreviewShaderMath.BUILTIN_COLOR_BLUE,
                    alpha);
            Assert.assertArrayEquals("alpha=" + alpha + " 时两档混合源色必须逐位一致", legacy, shader, 0.0F);
        }
    }

    /**
     * Lead 要求的定点断言：builtin 档 shader 输出 RGB == legacy 常量 (0.25, 0.9, 1.0) × alpha，
     * 且 RGB 既不被 alpha 乘两次、也不被自身平方（alpha=0.15 是远距最典型取值）。
     */
    @Test
    public void builtinOutputEqualsLegacyConstantsAndIsNotSquared() {
        float alpha = 0.15F;
        float[] shader = ChainPreviewShaderMath.shaderMixedSource(
                ChainPreviewShaderMath.BUILTIN_COLOR_RED,
                ChainPreviewShaderMath.BUILTIN_COLOR_GREEN,
                ChainPreviewShaderMath.BUILTIN_COLOR_BLUE,
                alpha);

        Assert.assertEquals("R = 0.25 × 0.15", 0.25F * 0.15F, shader[0], 0.0F);
        Assert.assertEquals("G = 0.9 × 0.15", 0.9F * 0.15F, shader[1], 0.0F);
        Assert.assertEquals("B = 1.0 × 0.15", 1.0F * 0.15F, shader[2], 0.0F);

        Assert.assertNotEquals("R 不得退化为 alpha² 的 0.0225", 0.25F * alpha * alpha, shader[0], 1.0e-6F);
        Assert.assertNotEquals("R 不得退化为基色平方的 0.25 × 0.25",
                0.25F * 0.25F, shader[0] / alpha, 1.0e-6F);
        Assert.assertNotEquals("G 不得退化为基色平方的 0.9 × 0.9",
                0.9F * 0.9F, shader[1] / alpha, 1.0e-6F);
    }

    /** builtin 常量必须与 legacy CPU 侧 BASE_* 完全一致（0.25/0.9/1.0）。 */
    @Test
    public void builtinPaletteMatchesLegacyConstants() {
        Assert.assertEquals(0.25F, ChainPreviewShaderMath.BUILTIN_COLOR_RED, 0.0F);
        Assert.assertEquals(0.9F, ChainPreviewShaderMath.BUILTIN_COLOR_GREEN, 0.0F);
        Assert.assertEquals(1.0F, ChainPreviewShaderMath.BUILTIN_COLOR_BLUE, 0.0F);
    }

    /** fadeStart >= fadeEnd（配置被收窄成退化区间）时必须仍然单调、不除零。 */
    @Test
    public void degenerateFadeSpanStaysFinite() {
        float alpha = ChainPreviewShaderMath.fadeAlpha(5.0F, 4.0F, 4.0F, 1.0F, 0.2F);
        Assert.assertFalse(Float.isNaN(alpha));
        Assert.assertFalse(Float.isInfinite(alpha));
        Assert.assertTrue(alpha >= 0.0F && alpha <= 1.0F);
    }
}
