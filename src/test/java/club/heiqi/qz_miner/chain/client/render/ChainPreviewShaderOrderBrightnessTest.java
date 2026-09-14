package club.heiqi.qz_miner.chain.client.render;

import java.util.HashSet;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;

/**
 * 连锁序渐弱（appearOrder → **颜色亮度**权重）的行为契约。
 *
 * <p>能力的真源只有两处：{@code preview.vert} 的 orderWeight 分支与 CPU 参考模型
 * {@link ChainPreviewShaderMath#orderWeight}。GLSL 在离线阶段无法编译，因此断言全部打在
 * <b>数值行为</b>上（float32 复算见工作站 {@code temp/qz-miner-order-weight-check.py} 与
 * {@code temp/qz-miner-order-brightness-math.py}）：</p>
 * <ol>
 *   <li><b>两个端点</b>：起点（order = 0）精确 1.0；最远（order &ge; 目标总数）等于配置下限
 *       （默认 0.55）；</li>
 *   <li><b>中间线性</b>：与「下限 + (1 − 下限) × (1 − 归一化序号)」这一独立闭式在整张网格上一致
 *       （容差 1e-6；100/256 处 Python 复算 = 0.82421875，与生产函数逐位相同），
 *       且权重随序号单调不增、恒落在 [下限, 1]；</li>
 *   <li><b>三条恒等出口</b>：下限 &ge; 1.0（关闭档）、出现序号未定义（u16 = 0xFFFF）、
 *       无同代目标总数（{@code uAppearSpan <= 0}）都必须<b>精确</b>返回 1.0 —— 这就是
 *       「1.0 = 关闭本能力并逐值等于现状」的可断言形式；</li>
 *   <li><b>作用位置（用户裁定）</b>：权重只乘颜色亮度（{@code color = color * orderWeight}），
 *       顶点 alpha 只由「距离淡出 × 逐波生长 × uFadeAlpha」决定 ⇒ 距离淡出与连锁序是两个
 *       <b>独立维度</b>、两个配置各自权威，不再抢同一个量
 *       （见 {@link #orderBrightnessAndDistanceFadeAreIndependentDimensions()}）；</li>
 *   <li><b>接线</b>：配置默认经读取面进入 plan，再由后端算成 {@code uOrderMinBrightness}；
 *       NaN / 越界一律收敛为恒等，且该 uniform 已登记在必备清单里。</li>
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
public class ChainPreviewShaderOrderBrightnessTest {

    /** 配置默认下限（{@code clientPreviewOrderMinBrightness} = 0.55）。 */
    private static final float DEFAULT_ORDER_MIN_BRIGHTNESS = 0.55F;

    /** 同代目标总数（生长与权重共用的归一化分母）。 */
    private static final float SPAN = 256.0F;

    /** 距离淡出档与两端取值（生产默认：2 格内 0.78、6 格外 0.15）。 */
    private static final float FADE_START = 2.0F;
    private static final float FADE_END = 6.0F;
    private static final float ALPHA_NEAR = 0.78F;
    private static final float ALPHA_FAR = 0.15F;

    /** 语义主色 G 通道（builtin 档精确基线常量），用于「权重乘在亮度上」的断言。 */
    private static final float BASE_GREEN = 0.9F;

    /** 起点 1.0、最远 = 配置下限（默认档 0.55）。 */
    @Test
    public void originIsOneAndFarthestIsTheConfiguredFloor() {
        Assert.assertEquals("起点（order = 0）必须精确为 1.0",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, 0.0F, SPAN), 0.0F);
        for (float order : new float[] {SPAN, SPAN + 1.0F, 4096.0F, 65534.0F}) {
            Assert.assertEquals("序号 >= 目标总数（最远）必须等于配置下限",
                DEFAULT_ORDER_MIN_BRIGHTNESS,
                ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, order, SPAN), 1.0e-6F);
        }
        // 单目标链路（span = 1）：order = 0 既是起点也是尽头，必须保持 1.0，不得被压到下限。
        Assert.assertEquals("单目标链路（span = 1）的唯一点必须是 1.0",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, 0.0F, 1.0F), 0.0F);
        // Python 独立复算（float32）：100/256 处 = 0.82421875，二进制精确。
        Assert.assertEquals("1/4 处必须线性插值（Python 复算 0.82421875）",
            0.82421875F,
            ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, 100.0F, SPAN), 1.0e-6F);
    }

    /** 中间段与独立闭式一致，且随序号单调不增、恒落在 [下限, 1]。 */
    @Test
    public void interiorMatchesIndependentLinearRampAndNeverIncreases() {
        long cells = 0L;
        for (float span : new float[] {1.0F, 2.0F, 7.0F, 16.0F, 256.0F, 4096.0F}) {
            float previous = 2.0F;
            for (int order = 0; order <= (int) Math.min(span, 600.0F); order++) {
                float weight = ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, order, span);
                Assert.assertEquals("span=" + span + " order=" + order + " 必须等于线性斜坡",
                    independentLinearRamp(DEFAULT_ORDER_MIN_BRIGHTNESS, order, span), weight, 1.0e-6F);
                Assert.assertTrue("权重必须落在 [下限, 1]（span=" + span + " order=" + order
                        + " 实际 " + weight + "）",
                    weight >= DEFAULT_ORDER_MIN_BRIGHTNESS - 1.0e-6F && weight <= 1.0F + 1.0e-9F);
                Assert.assertTrue("序号越大权重不得变大（span=" + span + " order=" + order + "）",
                    weight <= previous + 1.0e-7F);
                previous = weight;
                cells++;
            }
        }
        Assert.assertTrue("网格格数必须足够（实际 " + cells + "）", cells >= 500L);
    }

    /** 三条恒等出口精确返回 1.0（关闭档 / 序号未定义 / 无目标总数），且下限收敛不产生非法值。 */
    @Test
    public void identityExitsReturnExactlyOne() {
        for (float order : new float[] {0.0F, 1.0F, 100.0F, SPAN, 65534.0F}) {
            Assert.assertEquals("下限 = 1.0（关闭本能力）必须逐值恒等",
                1.0F, ChainPreviewShaderMath.orderWeight(
                    ChainPreviewShaderMath.ORDER_MIN_BRIGHTNESS_OFF, order, SPAN), 0.0F);
            Assert.assertEquals("下限 > 1.0 同样恒等（GLSL 门控是 uOrderMinBrightness < 1.0）",
                1.0F, ChainPreviewShaderMath.orderWeight(1.5F, order, SPAN), 0.0F);
        }
        for (float span : new float[] {0.0F, -1.0F, Float.NaN}) {
            Assert.assertEquals("无同代目标总数（span <= 0 / NaN）必须恒等",
                1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, 128.0F, span), 0.0F);
        }
        Assert.assertEquals("未定义序号（0xFFFF）必须恒等，不得按 65535 算成最远",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, 65535.0F, SPAN), 0.0F);
        Assert.assertEquals("NaN 序号按 GLSL 的 appearOrder < 65535.0 判定同样走恒等出口",
            1.0F, ChainPreviewShaderMath.orderWeight(DEFAULT_ORDER_MIN_BRIGHTNESS, Float.NaN, SPAN), 0.0F);

        Assert.assertEquals("NaN 下限必须收敛为恒等值（不得把远处条柱亮度压成纯黑）",
            1.0F, ChainPreviewShaderMath.orderMinBrightness(Float.NaN), 0.0F);
        Assert.assertEquals("正无穷下限必须收敛为恒等值",
            1.0F, ChainPreviewShaderMath.orderMinBrightness(Float.POSITIVE_INFINITY), 0.0F);
        Assert.assertEquals("越界下限收窄到 1.0", 1.0F, ChainPreviewShaderMath.orderMinBrightness(2.0F), 0.0F);
        Assert.assertEquals("负下限收窄到 0.0", 0.0F, ChainPreviewShaderMath.orderMinBrightness(-1.0F), 0.0F);
        Assert.assertEquals("合法下限原样保留", 0.55F, ChainPreviewShaderMath.orderMinBrightness(0.55F), 0.0F);
    }

    /**
     * 核心语义（用户裁定）：距离淡出与连锁序是<b>两个独立维度</b>——权重只乘颜色亮度，alpha 不含它。
     *
     * <p>断言形式：固定顶点（距离 / 进度 / 包络都不变）时，alpha 组合
     * （{@code fade * growth * uFadeAlpha}，经生产参考入口 {@link ChainPreviewShaderMath#finalAlpha}
     * 组合）在「权重开启（下限 0.55）」与「关闭（下限 1.0）」以及极端下限 0.0 三档下必须<b>逐值相同</b>，
     * 且近端 / 远端 alpha 的比值恒等于距离淡出曲线自身的 {@code 0.78 : 0.15}（Python 复算 5.1999998）。
     * 若有人把 orderWeight 乘回 alpha（旧形态），最远端会被额外压低 0.55 倍，该比值立刻变成 9.454545
     * ——这正是用户反馈的「两套『越远越淡』抢同一个量、配置语义互相污染」。</p>
     *
     * <p>同时断言另一半确实在动：亮度乘数在最远端必须等于权重下限、在起点恒为 1.0，
     * 且基色乘 1.0 逐位不变。否则本用例会退化成「什么都没发生」的空转断言。</p>
     */
    @Test
    public void orderBrightnessAndDistanceFadeAreIndependentDimensions() {
        float fadeNear = ChainPreviewShaderMath.fadeAlpha(1.0F, FADE_START, FADE_END, ALPHA_NEAR, ALPHA_FAR);
        float fadeFar = ChainPreviewShaderMath.fadeAlpha(8.0F, FADE_START, FADE_END, ALPHA_NEAR, ALPHA_FAR);
        Assert.assertEquals("前提：2 格内必须取到近端 alpha", ALPHA_NEAR, fadeNear, 0.0F);
        Assert.assertEquals("前提：6 格外必须取到远端 alpha", ALPHA_FAR, fadeFar, 0.0F);
        // 默认档：整段可见（生长恒等）、包络不透明 ⇒ alpha 这一维只剩距离淡出。
        float growth = ChainPreviewShaderMath.growthWeight(false, 1.0F, SPAN, SPAN);
        float envelope = 1.0F;
        float fadeRatio = ALPHA_NEAR / ALPHA_FAR;
        for (float floor : new float[] {
                ChainPreviewShaderMath.ORDER_MIN_BRIGHTNESS_OFF, DEFAULT_ORDER_MIN_BRIGHTNESS, 0.0F}) {
            float alphaNear = alphaLine(fadeNear, growth, envelope);
            float alphaFar = alphaLine(fadeFar, growth, envelope);
            Assert.assertEquals("下限 " + floor + "：近端 alpha 必须只由距离淡出决定",
                ALPHA_NEAR, alphaNear, 0.0F);
            Assert.assertEquals("下限 " + floor + "：远端 alpha 必须只由距离淡出决定",
                ALPHA_FAR, alphaFar, 0.0F);
            Assert.assertEquals("下限 " + floor + "：近 / 远 alpha 比值必须仍是淡出曲线自身的比",
                fadeRatio, alphaNear / alphaFar, 1.0e-6F);

            Assert.assertEquals("下限 " + floor + "：起点亮度乘数必须精确 1.0",
                1.0F, ChainPreviewShaderMath.orderWeight(floor, 0.0F, SPAN), 0.0F);
            Assert.assertEquals("下限 " + floor + "：最远端亮度乘数必须等于权重下限",
                floor, ChainPreviewShaderMath.orderWeight(floor, SPAN, SPAN), 1.0e-6F);
            Assert.assertEquals("下限 " + floor + "：近端颜色必须逐位不变（乘 1.0 精确恒等）",
                Float.floatToIntBits(BASE_GREEN),
                Float.floatToIntBits(BASE_GREEN * ChainPreviewShaderMath.orderWeight(floor, 0.0F, SPAN)));
            Assert.assertEquals("下限 " + floor + "：远端颜色必须等于基色 × 权重（Python 复算 0.4950000047683716）",
                BASE_GREEN * floor,
                brightnessLine(BASE_GREEN, floor, SPAN), 1.0e-6F);
        }
    }

    /** 全链路接线：配置默认 → 读取面 → plan → {@code uOrderMinBrightness}，且该 uniform 已登记。 */
    @Test
    public void uniformValueFollowsPlanAndNarrowsIllegalInput() {
        Assert.assertEquals("配置默认必须经读取面进入快照",
            0.55F, ChainPreviewVisualSettings.fromConfig().getOrderMinBrightness(), 1.0e-6F);
        Assert.assertEquals("配置默认必须原样成为 uOrderMinBrightness",
            0.55F, ChainPreviewShaderBackend.orderMinBrightnessFor(planWithOrderMinBrightness(0.55F)), 0.0F);
        Assert.assertEquals("1.0（关闭本能力）必须原样进 uniform",
            1.0F, ChainPreviewShaderBackend.orderMinBrightnessFor(planWithOrderMinBrightness(1.0F)), 0.0F);
        Assert.assertEquals("NaN 下限必须收敛为恒等，不把 NaN 送进 uniform",
            1.0F, ChainPreviewShaderBackend.orderMinBrightnessFor(planWithOrderMinBrightness(Float.NaN)), 0.0F);
        Assert.assertEquals("越界下限必须收敛到 1.0",
            1.0F, ChainPreviewShaderBackend.orderMinBrightnessFor(planWithOrderMinBrightness(3.0F)), 0.0F);
        Assert.assertEquals("DrawPlan 基线档必须是恒等（拿不到配置 != 用户选了 0.55）",
            1.0F, ChainPreviewDrawPlan.Visuals.BASELINE.getOrderMinBrightness(), 0.0F);

        Set<String> registered = new HashSet<String>();
        for (String name : ChainPreviewShaderProgram.requiredUniforms()) {
            registered.add(name);
        }
        for (String name : ChainPreviewShaderProgram.capabilityUniforms()) {
            registered.add(name);
        }
        Assert.assertTrue("uOrderMinBrightness 必须登记在 uniform 清单（缺失即程序不可用，功能会静默失效）",
            registered.contains("uOrderMinBrightness"));
    }

    /**
     * GLSL alpha 行的 CPU 复刻（{@code preview.vert}: {@code float alpha = fade * growth * uFadeAlpha;}）。
     *
     * <p>组合走生产参考入口 {@link ChainPreviewShaderMath#finalAlpha}（其 javadoc 与 GLSL 同一行同源）。
     * 入参<b>刻意不含 appearOrder / 权重下限</b>——它们不在这一行里，这正是「独立维度」的实现形态：
     * 本方法签名本身就是断言的一部分。</p>
     */
    private static float alphaLine(float fade, float growth, float envelope) {
        return ChainPreviewShaderMath.finalAlpha(fade * growth, envelope);
    }

    /** GLSL 亮度行的 CPU 复刻（{@code preview.vert}: {@code color = color * orderWeight;}）。 */
    private static float brightnessLine(float baseChannel, float orderMinBrightness, float order) {
        return baseChannel * ChainPreviewShaderMath.orderWeight(orderMinBrightness, order, SPAN);
    }

    /** 独立闭式（不复用生产函数）：下限 + (1 − 下限) × (1 − 归一化序号)。 */
    private static float independentLinearRamp(float orderMinBrightness, float order, float span) {
        float normalized = Math.min(order, span) / Math.max(span, 1.0F);
        float t = normalized < 0.0F ? 0.0F : (normalized > 1.0F ? 1.0F : normalized);
        return orderMinBrightness + (1.0F - orderMinBrightness) * (1.0F - t);
    }

    /** 指定下限的 plan（raw 构造：不做 sanitize，故 NaN / 越界会被原样带进来）。 */
    private static ChainPreviewDrawPlan planWithOrderMinBrightness(float orderMinBrightness) {
        return new ChainPreviewDrawPlan(
                0, 24, null,
                new ChainPreviewDrawPlan.Visuals(
                        0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
                        ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
                        ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, ChainPreviewDrawPlan.Visuals.Lod.OFF,
                        false, 0.0F, false, orderMinBrightness),
                ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
                0, 0, 0, 8, false, 0, 0L, 0L);
    }
}
