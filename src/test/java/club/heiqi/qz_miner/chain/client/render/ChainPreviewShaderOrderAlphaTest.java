package club.heiqi.qz_miner.chain.client.render;

import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;

/**
 * 连锁序渐弱（appearOrder → alpha 权重，本批新增）的行为契约。
 *
 * <p>能力的真源只有两处：{@code preview.vert} 的 orderWeight 分支与 CPU 参考模型
 * {@link ChainPreviewShaderMath#orderWeight}。GLSL 在离线阶段无法编译，因此断言全部打在
 * <b>数值行为</b>上（float32 复算见工作站 {@code temp/qz-miner-order-weight-check.py}）：</p>
 * <ol>
 *   <li><b>两个端点</b>：起点（order = 0）精确 1.0；最远（order &ge; 目标总数）等于配置下限
 *       （默认 0.45）；</li>
 *   <li><b>中间线性</b>：与「下限 + (1 − 下限) × (1 − 归一化序号)」这一独立闭式在整张网格上一致
 *       （容差 1e-6，实测最坏偏差 6e-8），且权重随序号单调不增、恒落在 [下限, 1]；</li>
 *   <li><b>三条恒等出口</b>：下限 &ge; 1.0（关闭档）、出现序号未定义（u16 = 0xFFFF）、
 *       无同代目标总数（{@code uAppearSpan <= 0}）都必须<b>精确</b>返回 1.0 —— 这就是
 *       「1.0 = 关闭本能力并逐值等于现状」的可断言形式；</li>
 *   <li><b>接线</b>：配置默认经读取面进入 plan，再由后端算成 {@code uOrderMinAlpha}；NaN / 越界
 *       一律收敛为恒等，且该 uniform 已登记在必备清单里。</li>
 * </ol>
 *
 * <p>GLSL 源码文本不做断言（重命名即误报、改系数却照样绿）：GLSL 逻辑改动走真机验证 +
 * shader 头部「实机验证记录」标记。</p>
 *
 * <p><b>默认档可见性</b>：本能力要在静止预览（{@code animation=off}，进度恒 1）下生效，
 * 因此 {@code uAppearSpan} 必须恒携带真实序号总数——推导见
 * {@code ChainPreviewShaderBackend#growthUniforms}（旧写法在 u &gt;= 1 时把 span 写成 0，
 * 会让本能力恒等失效）。{@code uAppearSpan <= 0} 现在只表示「无序号信息」（无 aAux），
 * 仍是恒等出口。</p>
 */
public class ChainPreviewShaderOrderAlphaTest {

    /** 配置默认下限（{@code clientPreviewOrderMinAlpha} = 0.45）。 */
    private static final float DEFAULT_ORDER_MIN_ALPHA = 0.45F;

    /** 同代目标总数（生长与权重共用的归一化分母）。 */
    private static final float SPAN = 256.0F;

    /** 起点 1.0、最远 = 配置下限（默认档 0.45）。 */
    @Test
    public void originIsOneAndFarthestIsTheConfiguredFloor() {
        Assert.assertEquals("起点（order = 0）必须精确为 1.0",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, 0.0F, SPAN), 0.0F);
        for (float order : new float[] {SPAN, SPAN + 1.0F, 4096.0F, 65534.0F}) {
            Assert.assertEquals("序号 >= 目标总数（最远）必须等于配置下限",
                DEFAULT_ORDER_MIN_ALPHA,
                ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, order, SPAN), 1.0e-6F);
        }
        // 单目标链路（span = 1）：order = 0 既是起点也是尽头，必须保持 1.0，不得被压到下限。
        Assert.assertEquals("单目标链路（span = 1）的唯一点必须是 1.0",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, 0.0F, 1.0F), 0.0F);
        // Python 独立复算：100/256 处 = 0.78515625（二进制精确）
        Assert.assertEquals("1/4 处必须线性插值（Python 复算 0.78515625）",
            0.78515625F,
            ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, 100.0F, SPAN), 1.0e-6F);
    }

    /** 中间段与独立闭式一致，且随序号单调不增、恒落在 [下限, 1]。 */
    @Test
    public void interiorMatchesIndependentLinearRampAndNeverIncreases() {
        long cells = 0L;
        for (float span : new float[] {1.0F, 2.0F, 7.0F, 16.0F, 256.0F, 4096.0F}) {
            float previous = 2.0F;
            for (int order = 0; order <= (int) Math.min(span, 600.0F); order++) {
                float weight = ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, order, span);
                Assert.assertEquals("span=" + span + " order=" + order + " 必须等于线性斜坡",
                    independentLinearRamp(DEFAULT_ORDER_MIN_ALPHA, order, span), weight, 1.0e-6F);
                Assert.assertTrue("权重必须落在 [下限, 1]（span=" + span + " order=" + order
                        + " 实际 " + weight + "）",
                    weight >= DEFAULT_ORDER_MIN_ALPHA - 1.0e-6F && weight <= 1.0F + 1.0e-9F);
                Assert.assertTrue("序号越大权重不得变大（span=" + span + " order=" + order + "）",
                    weight <= previous + 1.0e-7F);
                previous = weight;
                cells++;
            }
        }
        Assert.assertTrue("网格格数必须足够（实际 " + cells + "）", cells >= 500L);
    }

    /** 三条恒等出口精确返回 1.0（关闭档 / 序号未定义 / 无目标总数）。 */
    @Test
    public void identityExitsReturnExactlyOne() {
        for (float order : new float[] {0.0F, 1.0F, 100.0F, SPAN, 65534.0F}) {
            Assert.assertEquals("下限 = 1.0（关闭本能力）必须逐值恒等",
                1.0F, ChainPreviewShaderMath.orderWeight(
                    ChainPreviewShaderMath.ORDER_MIN_ALPHA_OFF, order, SPAN), 0.0F);
            Assert.assertEquals("下限 > 1.0 同样恒等（GLSL 门控是 uOrderMinAlpha < 1.0）",
                1.0F, ChainPreviewShaderMath.orderWeight(1.5F, order, SPAN), 0.0F);
        }
        for (float span : new float[] {0.0F, -1.0F, Float.NaN}) {
            Assert.assertEquals("无同代目标总数（span <= 0 / NaN）必须恒等",
                1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, 128.0F, span), 0.0F);
        }
        Assert.assertEquals("未定义序号（0xFFFF）必须恒等，不得按 65535 算成最远",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, 65535.0F, SPAN), 0.0F);
        Assert.assertEquals("NaN 序号按 GLSL 的 appearOrder < 65535.0 判定同样走恒等出口",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_ALPHA, Float.NaN, SPAN), 0.0F);

        Assert.assertEquals("NaN 下限必须收敛为恒等值（不得让远处条柱静默消失）",
            1.0F, ChainPreviewShaderMath.orderMinAlpha(Float.NaN), 0.0F);
        Assert.assertEquals("正无穷下限必须收敛为恒等值",
            1.0F, ChainPreviewShaderMath.orderMinAlpha(Float.POSITIVE_INFINITY), 0.0F);
        Assert.assertEquals("越界下限收窄到 1.0", 1.0F, ChainPreviewShaderMath.orderMinAlpha(2.0F), 0.0F);
        Assert.assertEquals("负下限收窄到 0.0", 0.0F, ChainPreviewShaderMath.orderMinAlpha(-1.0F), 0.0F);
        Assert.assertEquals("合法下限原样保留", 0.45F, ChainPreviewShaderMath.orderMinAlpha(0.45F), 0.0F);
    }

    /** 全链路接线：配置默认 → 读取面 → plan → {@code uOrderMinAlpha}，且该 uniform 已登记。 */
    @Test
    public void uniformValueFollowsPlanAndNarrowsIllegalInput() {
        Assert.assertEquals("配置默认必须经读取面进入快照",
            0.45F, ChainPreviewVisualSettings.fromConfig().getOrderMinAlpha(), 1.0e-6F);
        Assert.assertEquals("配置默认必须原样成为 uOrderMinAlpha",
            0.45F, ChainPreviewShaderBackend.orderMinAlphaFor(planWithOrderMinAlpha(0.45F)), 0.0F);
        Assert.assertEquals("1.0（关闭本能力）必须原样进 uniform",
            1.0F, ChainPreviewShaderBackend.orderMinAlphaFor(planWithOrderMinAlpha(1.0F)), 0.0F);
        Assert.assertEquals("NaN 下限必须收敛为恒等，不把 NaN 送进 uniform",
            1.0F, ChainPreviewShaderBackend.orderMinAlphaFor(planWithOrderMinAlpha(Float.NaN)), 0.0F);
        Assert.assertEquals("越界下限必须收敛到 1.0",
            1.0F, ChainPreviewShaderBackend.orderMinAlphaFor(planWithOrderMinAlpha(3.0F)), 0.0F);
        Assert.assertEquals("DrawPlan 基线档必须是恒等（拿不到配置 != 用户选了 0.45）",
            1.0F, ChainPreviewDrawPlan.Visuals.BASELINE.getOrderMinAlpha(), 0.0F);

        Set<String> registered = new HashSet<String>();
        for (String name : ChainPreviewShaderProgram.requiredUniforms()) {
            registered.add(name);
        }
        for (String name : ChainPreviewShaderProgram.capabilityUniforms()) {
            registered.add(name);
        }
        Assert.assertTrue("uOrderMinAlpha 必须登记在 uniform 清单（缺失即程序不可用，功能会静默失效）",
            registered.contains("uOrderMinAlpha"));
    }

    /** 独立闭式（不复用生产函数）：下限 + (1 − 下限) × (1 − 归一化序号)。 */
    private static float independentLinearRamp(float orderMinAlpha, float order, float span) {
        float normalized = Math.min(order, span) / Math.max(span, 1.0F);
        float t = normalized < 0.0F ? 0.0F : (normalized > 1.0F ? 1.0F : normalized);
        return orderMinAlpha + (1.0F - orderMinAlpha) * (1.0F - t);
    }

    /** 指定下限的 plan（raw 构造：不做 sanitize，故 NaN / 越界会被原样带进来）。 */
    private static ChainPreviewDrawPlan planWithOrderMinAlpha(float orderMinAlpha) {
        return new ChainPreviewDrawPlan(
                0, 24, null,
                new ChainPreviewDrawPlan.Visuals(
                        0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
                        ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
                        ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF,
                        false, 0.0F, false, orderMinAlpha),
                ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
                0, 0, 0, 8, false, 0, 0L, 0L);
    }
}
