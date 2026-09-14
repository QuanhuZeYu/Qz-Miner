package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;

/**
 * 淡入淡出包络（B3.2 / L5）与最终 alpha 的契约。
 *
 * <p>规范要求：{@code fadeAlpha = 0} ⇒ 全透明、{@code 0.5} ⇒ 半透、{@code 1} ⇒ 与今天逐值一致，
 * 且与 legacy 乘子路径的最终 alpha 逐值相等。这里用参考模型逐值断言（纯 JVM）；
 * 后端把 plan 的包络映射成 uniform 取值的那一步、以及宿主安全初值同样是纯函数/常量
 * （{@link ChainPreviewShaderBackend#fadeAlphaFor(ChainPreviewDrawPlan)}、
 * {@link ChainPreviewShaderProgram#sanitizeFadeAlpha(float)}、
 * {@link ChainPreviewShaderProgram#INITIAL_FADE_ALPHA}），按数值断言。
 * 「GLSL 真的乘了该 uniform」不再用源码文本断言，改为「uFadeAlpha 登记为硬必备 uniform
 * + 真机验证 + shader 头部「实机验证记录」标记」（注释改动本身不触发重验）。</p>
 */
public class ChainPreviewShaderFadeAlphaTest {

    private static final float FADE_START = 2.0F;
    private static final float FADE_END = 6.0F;
    private static final float MAX_ALPHA = 0.8F;
    private static final float MIN_ALPHA = 0.2F;

    /** fadeAlpha=1 必须与现状逐值一致（乘 1 不改变任何结果）。 */
    @Test
    public void fadeAlphaOneMatchesTodayExactly() {
        for (int step = 0; step <= 200; step++) {
            float distance = 0.0F + 10.0F * step / 200.0F;
            float today = ChainPreviewShaderMath.fadeAlpha(distance, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA);
            float withEnvelope = ChainPreviewShaderMath.finalAlpha(today, 1.0F);
            Assert.assertEquals("fadeAlpha=1 在距离 " + distance + " 处必须逐值一致", today, withEnvelope, 0.0F);
        }
    }

    /** fadeAlpha=0 ⇒ 全透明；0.5 ⇒ 半透（与距离淡出线性相乘）。 */
    @Test
    public void fadeAlphaZeroIsFullyTransparentAndHalfIsHalf() {
        for (int step = 0; step <= 200; step++) {
            float distance = 0.0F + 10.0F * step / 200.0F;
            float today = ChainPreviewShaderMath.fadeAlpha(distance, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA);

            Assert.assertEquals("fadeAlpha=0 必须全透明", 0.0F,
                    ChainPreviewShaderMath.finalAlpha(today, 0.0F), 0.0F);
            Assert.assertEquals("fadeAlpha=0.5 必须是距离淡出的一半",
                    today * 0.5F, ChainPreviewShaderMath.finalAlpha(today, 0.5F), 0.0F);
        }
    }

    /**
     * 与 legacy 乘子路径逐值一致。
     *
     * <p>legacy 把包络乘进 CPU 颜色流的 alpha；shader 把它乘进顶点 alpha。
     * 两者必须是同一乘法，否则同一场景在两档不同亮度。</p>
     */
    @Test
    public void shaderEnvelopeMatchesLegacyMultiplier() throws Exception {
        VisualParameters cpuVisuals = new VisualParameters(
                0.0D, 0.0D, 0.0D, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA, 0.045F);
        java.lang.reflect.Method alphaFor = VisualParameters.class.getDeclaredMethod(
                "alphaFor", double.class, double.class, double.class);
        alphaFor.setAccessible(true);

        for (int envelopeStep = 0; envelopeStep <= 20; envelopeStep++) {
            float fadeAlpha = envelopeStep / 20.0F;
            for (int distanceStep = 0; distanceStep <= 100; distanceStep++) {
                double distance = 10.0D * distanceStep / 100.0D;

                // legacy 侧：CPU 用真实的 alphaFor（double）得距离 alpha，再乘包络
                float cpuDistanceAlpha = ((Float) alphaFor.invoke(cpuVisuals, distance, 0.0D, 0.0D)).floatValue();
                float legacyAlpha = cpuDistanceAlpha * fadeAlpha;

                // shader 侧：参考模型 = fadeAlpha(quadratic) × 包络
                float shaderDistanceAlpha = ChainPreviewShaderMath.fadeAlpha(
                        (float) distance, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA);
                float shaderAlpha = ChainPreviewShaderMath.finalAlpha(shaderDistanceAlpha, fadeAlpha);

                Assert.assertEquals("包络=" + fadeAlpha + " 距离=" + distance + " 两档最终 alpha 必须一致",
                        legacyAlpha, shaderAlpha, 1.0e-5F);
            }
        }
    }

    /** 包络必须被 clamp 到 [0,1]；NaN 收敛为 1（不透明），避免异常配置导致整链透明。 */
    @Test
    public void envelopeIsClampedAndNaNIsSafe() {
        Assert.assertEquals("负包络 clamp 到 0", 0.0F,
                ChainPreviewShaderMath.finalAlpha(0.7F, -1.0F), 0.0F);
        Assert.assertEquals("超界包络 clamp 到 1", 0.7F,
                ChainPreviewShaderMath.finalAlpha(0.7F, 2.0F), 0.0F);
        float nanResult = ChainPreviewShaderMath.finalAlpha(0.7F, Float.NaN);
        Assert.assertFalse("NaN 包络不得产生 NaN alpha", Float.isNaN(nanResult));
    }

    // ------------------------------------------------------------------ 后端 uniform 数值（plan → uniform）

    /**
     * 后端把 plan 的包络映射成 {@code uFadeAlpha} 的取值，且取值恒在 [0,1]。
     *
     * <p>断言打在 {@link ChainPreviewShaderBackend#fadeAlphaFor(ChainPreviewDrawPlan)} 的<b>数值</b>上
     * ——「给定 plan 产出什么包络」才是接线契约；「源码里有没有写 {@code plan.getFadeAlpha()} /
     * {@code program.setFadeAlpha(}」重命名即误报、改成恒传 1.0 却照样绿（那会让淡入淡出静默失效）。</p>
     */
    @Test
    public void fadeAlphaUniformFollowsPlanAndStaysInUnitRange() {
        Assert.assertEquals("包络必须原样进 uniform", 0.5F,
                ChainPreviewShaderBackend.fadeAlphaFor(planWithFadeAlpha(0.5F)), 0.0F);
        Assert.assertEquals("fadeAlpha=0 ⇒ 全透明", 0.0F,
                ChainPreviewShaderBackend.fadeAlphaFor(planWithFadeAlpha(0.0F)), 0.0F);
        Assert.assertEquals("fadeAlpha=1 ⇒ 与今天逐值一致", 1.0F,
                ChainPreviewShaderBackend.fadeAlphaFor(planWithFadeAlpha(1.0F)), 0.0F);
        Assert.assertEquals("负包络必须收敛为全透明", 0.0F,
                ChainPreviewShaderBackend.fadeAlphaFor(planWithFadeAlpha(-3.0F)), 0.0F);
        Assert.assertEquals("超界包络必须收敛为不透明", 1.0F,
                ChainPreviewShaderBackend.fadeAlphaFor(planWithFadeAlpha(4.0F)), 0.0F);
        Assert.assertEquals("NaN 包络必须收敛为不透明（不得让整链静默消失）", 1.0F,
                ChainPreviewShaderBackend.fadeAlphaFor(planWithFadeAlpha(Float.NaN)), 0.0F);

        // 宿主安全初值：uniform 未赋值时为 0，漏设会让整链透明 ⇒ 初值必须是不透明的 1（精确值）
        Assert.assertEquals("安全初值必须精确为 1.0（不透明）", 1.0F,
                ChainPreviewShaderProgram.INITIAL_FADE_ALPHA, 0.0F);
        Assert.assertEquals("安全初值必须原样通过收敛（不被 clamp 改动）", 1.0F,
                ChainPreviewShaderProgram.sanitizeFadeAlpha(ChainPreviewShaderProgram.INITIAL_FADE_ALPHA), 0.0F);
    }

    /**
     * 无 GL 上下文时写包络必须安全退化：{@link ChainPreviewShaderProgram#setFadeAlpha(float)}
     * 存在且可调用，绝不向渲染帧抛异常（uniform 写不进去只是能力缺失，不是故障）。
     */
    @Test
    public void fadeAlphaUploadDegradesSafelyWithoutGlContext() {
        ChainPreviewShaderProgram program = new ChainPreviewShaderProgram();
        program.ensureReady();
        try {
            for (float envelope : new float[] {0.0F, 0.5F, 1.0F, -1.0F, 2.0F, Float.NaN}) {
                program.setFadeAlpha(envelope);
            }
        } catch (Throwable failure) {
            Assert.fail("setFadeAlpha 不得向渲染帧抛出: " + failure);
        }
    }

    /** 指定包络的 plan（raw 构造：不做 sanitize，故 NaN / 越界会被原样带进来）。 */
    private static ChainPreviewDrawPlan planWithFadeAlpha(float fadeAlpha) {
        return new ChainPreviewDrawPlan(
                0, 24, null,
                new ChainPreviewDrawPlan.Visuals(
                        0.045F, 0.0F, 1.0F, FADE_START, FADE_END, MAX_ALPHA, MIN_ALPHA,
                        ChainPreviewDrawPlan.DepthChannel.XRAY, fadeAlpha),
                ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
                0, 0, 0, 8, false, 0, 0L, 0L);
    }
}
