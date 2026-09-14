package club.heiqi.qz_miner.chain.client.render;

import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;

/**
 * 内部结构亮度系数（{@code clientPreviewInteriorDim}，"外轮廓强 / 内部格线弱"）的行为契约。
 *
 * <p>能力的真源只有两处：{@code preview.vert} 的内部压暗分支与 CPU 参考模型
 * {@link ChainPreviewShaderMath#interiorDim}。GLSL 在离线阶段无法编译，因此断言全部打在
 * <b>数值行为</b>上（float32 期望值由 Python 独立复算，见工作站
 * {@code temp/qz-miner-interior-dim-math.py}）：</p>
 * <ol>
 *   <li><b>收敛</b>：NaN / ±Infinity → 恒等值 1.0；越界 clamp 到 [0,1]；合法值原样保留
 *       —— 这就是「不把非法值送进 uniform」的可断言形式；</li>
 *   <li><b>关闭档恒等</b>：{@code interiorDim(1.0) == 1.0}，且任意颜色通道乘它<b>逐位不变</b>
 *       （乘 1.0 是精确恒等），故关闭档逐值等于接线前；</li>
 *   <li><b>判据边界</b>：{@code auxChannel(aAux.y) >= 4.0} 这条门控在 CPU 侧复算下把 0..3
 *       （贯通管面 = 外轮廓）判为非内部、把 255（{@code AUX_UNDEFINED} = junction 补块 /
 *       共享顶点 = 内部格线）判为内部——复刻的是判据本身，不是「GPU 会怎么画」；</li>
 *   <li><b>接线</b>：配置默认经读取面进入 plan、再成为 {@code uInteriorDim}；基线档必须是恒等
 *       （「拿不到配置」不等于「用户选了 0.65」）；NaN / 越界一律收敛；该 uniform 已登记在
 *       必备清单里（缺失即程序不可用，功能会静默失效）。</li>
 * </ol>
 *
 * <p>GLSL 源码文本不做断言（重命名即误报、改系数却照样绿）：GLSL 逻辑改动走真机验证 +
 * shader 头部「实机验证记录」标记，本次变更处已按该口径写明「需真机重验」。</p>
 */
public class ChainPreviewInteriorDimTest {

    /** 配置默认系数（{@code clientPreviewInteriorDim} = 0.65）。 */
    private static final float DEFAULT_INTERIOR_DIM = 0.65F;

    /** 语义主色用的基线常量（builtin 档精确值），验证「内部压暗乘在颜色 rgb 上」。 */
    private static final float BASE_GREEN = 0.9F;
    private static final float BASE_RED = 0.25F;

    /** 关闭档（= 1.0）必须逐位恒等——各种通道值都试一遍。 */
    @Test
    public void disabledFactorIsBitExactIdentity() {
        float identity = ChainPreviewShaderMath.interiorDim(ChainPreviewShaderMath.INTERIOR_DIM_OFF);
        Assert.assertEquals("1.0 是恒等值，必须原样返回（不得被 clamp 改写）",
            1.0F, identity, 0.0F);
        for (float channel : new float[] {
                0.0F, BASE_RED, BASE_GREEN, 1.0F, 0.5F, 0.001F, Float.MIN_VALUE}) {
            Assert.assertEquals("通道 " + channel + " 乘关闭档必须逐位不变（乘 1.0 精确恒等）",
                Float.floatToIntBits(channel), Float.floatToIntBits(channel * identity));
        }
    }

    /** NaN / Infinity → 恒等；越界 clamp；合法值原样 —— 非法配置不得把内部格线压成纯黑。 */
    @Test
    public void illegalInputConvergesToIdentityAndRangeIsClamped() {
        Assert.assertEquals("NaN 必须收敛为恒等（不得传播，也不得压黑）",
            1.0F, ChainPreviewShaderMath.interiorDim(Float.NaN), 0.0F);
        Assert.assertEquals("正无穷必须收敛为恒等",
            1.0F, ChainPreviewShaderMath.interiorDim(Float.POSITIVE_INFINITY), 0.0F);
        Assert.assertEquals("负无穷必须收敛为恒等",
            1.0F, ChainPreviewShaderMath.interiorDim(Float.NEGATIVE_INFINITY), 0.0F);
        Assert.assertEquals("上越界收窄到 1.0（= 关闭）",
            1.0F, ChainPreviewShaderMath.interiorDim(1.5F), 0.0F);
        Assert.assertEquals("下越界收窄到 0.0（内部格线全黑，是合法档位）",
            0.0F, ChainPreviewShaderMath.interiorDim(-0.5F), 0.0F);
        Assert.assertEquals("合法值原样保留",
            DEFAULT_INTERIOR_DIM, ChainPreviewShaderMath.interiorDim(DEFAULT_INTERIOR_DIM), 0.0F);
        Assert.assertEquals("0.0 是合法值（不得被当成非法）",
            0.0F, ChainPreviewShaderMath.interiorDim(0.0F), 0.0F);
        Assert.assertEquals("负零同样落在 [0,1] 内，必须原样保留符号位（clamp 不参与）",
            Float.floatToIntBits(-0.0F),
            Float.floatToIntBits(ChainPreviewShaderMath.interiorDim(-0.0F)));
    }

    /**
     * 判据边界：{@code auxChannel(aAux.y)} 的量化往返下，贯通管面槽位与未定义哨兵必须分居阈值两侧。
     *
     * <p>quantization 与 {@code preview.vert} 的 {@code auxChannel} 同式（{@code floor(x * 255 + 0.5)}，
     * 两边算子同为 IEEE 单精度），期望值由 Python 复算（byte 3 → 3.5，byte 255 → 255.5）。</p>
     */
    @Test
    public void tubeEdgeSlotsStayOuterAndUndefinedSentinelIsInterior() {
        float threshold = ChainPreviewShaderMath.INTERIOR_TUBEEDGE_THRESHOLD;
        for (int byteValue : new int[] {0, 1, 2, 3}) {
            float channel = quantizedAuxChannel(byteValue);
            Assert.assertTrue("贯通管面槽位 " + byteValue + "（外轮廓）必须判为非内部（量化值 "
                    + channel + "）", channel < threshold);
        }
        float undefined = quantizedAuxChannel(255);
        Assert.assertTrue("AUX_UNDEFINED（junction 补块 / 共享顶点 = 内部格线）必须判为内部（量化值 "
                + undefined + "）", undefined >= threshold);
    }

    /**
     * 关闭档恒等 vs 开启档压暗：外轮廓（贯通面）不动，内部格线乘系数。
     *
     * <p>复刻的是 {@code preview.vert} 那一次乘法（{@code color = color * uInteriorDim}）与它的门控，
     * 用于钉住「只乘内部格线、关闭档逐位不变」这一语义；期望值：0.9 × 0.65 = 0.5849999785423279、
     * 0.25 × 0.65 = 0.16249999403953552（Python 复算，float32 精确值）。</p>
     *
     * <p>另一半义务由 {@link #disabledFactorIsBitExactIdentity()} 承担：关闭档不是「大概不变」
     * 而是逐位不变。</p>
     */
    @Test
    public void outerFacesStayAndInnerGridLinesAreDimmed() {
        float off = ChainPreviewShaderMath.INTERIOR_DIM_OFF;
        // 关闭档：两类顶点的颜色都必须逐位等于基色。
        for (int tubeEdge : new int[] {0, 1, 2, 3, 255}) {
            Assert.assertEquals("关闭档下 tubeEdge=" + tubeEdge + " 必须逐位保持基色",
                Float.floatToIntBits(BASE_GREEN),
                Float.floatToIntBits(interiorLine(BASE_GREEN, off, tubeEdge)));
        }
        // 开启档：贯通面（外轮廓）保持 1.0，内部格线乘系数。
        for (int tubeEdge : new int[] {0, 1, 2, 3}) {
            Assert.assertEquals("开启档下贯通管面槽位 " + tubeEdge + " 必须保持原亮度",
                Float.floatToIntBits(BASE_GREEN),
                Float.floatToIntBits(interiorLine(BASE_GREEN, DEFAULT_INTERIOR_DIM, tubeEdge)));
        }
        Assert.assertEquals("开启档下内部格线必须等于 基色 × 0.65（Python 复算 0.5849999785423279）",
            0.5849999785423279F,
            interiorLine(BASE_GREEN, DEFAULT_INTERIOR_DIM, 255), 0.0F);
        Assert.assertEquals("开启档下内部格线同样作用于 R 通道（Python 复算 0.16249999403953552）",
            0.16249999403953552F,
            interiorLine(BASE_RED, DEFAULT_INTERIOR_DIM, 255), 0.0F);
    }

    /** 全链路接线：配置默认 → 读取面 → plan → {@code uInteriorDim}，且该 uniform 已登记。 */
    @Test
    public void uniformValueFollowsPlanAndNarrowsIllegalInput() {
        Assert.assertEquals("配置默认必须经读取面进入快照",
            DEFAULT_INTERIOR_DIM, ChainPreviewVisualSettings.fromConfig().getInteriorDim(), 1.0e-6F);
        Assert.assertEquals("配置默认必须原样成为 uInteriorDim",
            DEFAULT_INTERIOR_DIM,
            ChainPreviewShaderBackend.interiorDimFor(planWithInteriorDim(DEFAULT_INTERIOR_DIM)), 0.0F);
        Assert.assertEquals("1.0（关闭本能力）必须原样进 uniform",
            1.0F, ChainPreviewShaderBackend.interiorDimFor(planWithInteriorDim(1.0F)), 0.0F);
        Assert.assertEquals("NaN 必须收敛为恒等，不把 NaN 送进 uniform",
            1.0F, ChainPreviewShaderBackend.interiorDimFor(planWithInteriorDim(Float.NaN)), 0.0F);
        Assert.assertEquals("越界必须收敛到 1.0",
            1.0F, ChainPreviewShaderBackend.interiorDimFor(planWithInteriorDim(3.0F)), 0.0F);
        Assert.assertEquals("DrawPlan 基线档必须是恒等（拿不到配置 != 用户选了 0.65）",
            1.0F, ChainPreviewDrawPlan.Visuals.BASELINE.getInteriorDim(), 0.0F);

        Set<String> registered = new HashSet<String>();
        for (String name : ChainPreviewShaderProgram.requiredUniforms()) {
            registered.add(name);
        }
        for (String name : ChainPreviewShaderProgram.capabilityUniforms()) {
            registered.add(name);
        }
        Assert.assertTrue("uInteriorDim 必须登记在 uniform 清单（缺失即程序不可用，功能会静默失效）",
            registered.contains("uInteriorDim"));
    }

    /**
     * {@code preview.vert} 颜色行的 CPU 复刻（含门控），派生状态直接取自
     * {@link ChainPreviewShaderMath#interiorDim} 与 {@link ChainPreviewShaderMath#INTERIOR_TUBEEDGE_THRESHOLD}
     * ——不为本测试在生产代码里加接缝，判据由参考模型给。
     */
    private static float interiorLine(float baseChannel, float requestedInteriorDim, int tubeEdgeByte) {
        float factor = ChainPreviewShaderMath.interiorDim(requestedInteriorDim);
        // 本次复刻刻意不含描边条件（uOutlineWidthPx <= 0.0）：本用例的顶点都是主体 pass 的顶点，
        // 描边互斥语义属 shader 分支，不在本用例的可证范围内。
        if (factor < 1.0F && quantizedAuxChannel(tubeEdgeByte)
                >= ChainPreviewShaderMath.INTERIOR_TUBEEDGE_THRESHOLD) {
            return baseChannel * factor;
        }
        return baseChannel;
    }

    /** {@code preview.vert} 的 {@code auxChannel}：normalized uint8 × 255 再四舍五入。 */
    private static float quantizedAuxChannel(int byteValue) {
        float normalized = byteValue / 255.0F;
        return (float) Math.floor(normalized * 255.0F + 0.5F);
    }

    /** 指定系数的 plan（raw 构造：不做 sanitize，故 NaN / 越界会被原样带进来）。 */
    private static ChainPreviewDrawPlan planWithInteriorDim(float interiorDim) {
        return new ChainPreviewDrawPlan(
                0, 24, null,
                new ChainPreviewDrawPlan.Visuals(
                        0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
                        ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
                        ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF,
                        false, 0.0F, false, 1.0F, interiorDim),
                ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
                0, 0, 0, 8, false, 0, 0L, 0L);
    }
}
