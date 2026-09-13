package club.heiqi.qz_miner.chain.client.render;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 波次 2 · 逐波生长 + 屏幕最小宽度的行为契约（task-11）。
 *
 * <p>两项效果都必须在纯 JVM 内可证伪：</p>
 * <ol>
 *   <li><b>逐波生长</b>：判据 {@code order <= round(u × 目标总数)} 落成「序号格之差」后，
 *       必须满足 u=0 全隐、u=1 全显、中间单调递增；0xFFFF 恒可见（不出现空洞）。</li>
 *   <li><b>屏幕最小宽度</b>：px=0 严格恒等（逐值相等，不是「误差内相等」）；
 *       px&gt;0 只对亚像素条柱加宽，近处（已够宽）保持不变。</li>
 * </ol>
 *
 * <p>同时用「GLSL 表达式同形」断言把着色器侧的算术固定下来：参考模型与 GLSL 必须
 * 逐式对应（相同的乘除与 clamp 顺序），否则离线断言就保护不了真机行为。</p>
 */
public class ChainPreviewShaderGrowthWidthTest {

    private static final String VERTEX_PATH = "src/main/resources/assets/qz_miner/shaders/preview.vert";
    private static final String BACKEND_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderBackend.java";

    /** 与 GLSL 一致的半厚度（0.045 / 2）。 */
    private static final float HALF_THICKNESS = 0.0225F;

    // ------------------------------------------------------------------ A) 逐波生长

    /**
     * u=0 ⇒ 全部「已定义序号」的顶点不可见；u=1 ⇒ 全部可见。
     *
     * <p>0xFFFF（未定义序号）是例外：它在任何进度都必须可见（Lead 要求「不产生空洞」），
     * 对应 GLSL 的 {@code if (appearOrder < 65535.0)} 之外 growth 保持初值 1.0。</p>
     */
    @Test
    public void growthHidesEverythingAtZeroAndShowsEverythingAtOne() {
        float total = 256.0F;
        for (float order : new float[] {0.0F, 1.0F, 5.0F, 250.0F, 255.0F}) {
            Assert.assertEquals("u=0 时 order=" + order + " 必须不可见",
                    0.0F, ChainPreviewShaderMath.growthWeight(true, 0.0F, order, total), 0.0F);
            Assert.assertEquals("u=1 时 order=" + order + " 必须完全可见",
                    1.0F, ChainPreviewShaderMath.growthWeight(true, 1.0F, order, total), 0.0F);
        }
        // 未定义序号：u=0 也必须可见（否则链路上会出现空洞）
        Assert.assertEquals("u=0 时未定义序号仍必须可见",
                1.0F, ChainPreviewShaderMath.growthWeight(true, 0.0F, 0xFFFF, total), 0.0F);
        Assert.assertEquals("u=1 时未定义序号必须可见",
                1.0F, ChainPreviewShaderMath.growthWeight(true, 1.0F, 0xFFFF, total), 0.0F);
    }

    /** 可见顶点数必须随 u 单调递增（「先显后隐」会破坏出现顺序语义）。 */
    @Test
    public void visibleOrderCountIsMonotonicInProgress() {
        float total = 256.0F;
        int previousVisible = -1;
        for (int step = 0; step <= 1000; step++) {
            float u = step / 1000.0F;
            int visible = 0;
            for (int order = 0; order < total; order++) {
                if (ChainPreviewShaderMath.growthWeight(true, u, order, total) > 0.0F) {
                    visible++;
                }
            }
            Assert.assertTrue("u=" + u + " 时可见数必须不减（前=" + previousVisible + " 现=" + visible + "）",
                    visible >= previousVisible);
            previousVisible = visible;
        }
        Assert.assertEquals("u→1 时必须接近全显", (int) total, previousVisible);
        Assert.assertEquals("u=0 时可见数必须为 0", 0,
                countVisible(0.0F, total));
    }

    /** 每个顶点的权重必须随 u 单调不减（整数序号的阶梯单调性）。 */
    @Test
    public void perVertexWeightIsMonotonicInProgress() {
        float total = 256.0F;
        for (float order : new float[] {0.0F, 1.0F, 7.0F, 128.0F, 255.0F}) {
            float previous = -1.0F;
            for (int step = 0; step <= 500; step++) {
                float weight = ChainPreviewShaderMath.growthWeight(true, step / 500.0F, order, total);
                Assert.assertTrue("order=" + order + " 权重必须单调不减", weight >= previous - 1.0e-7F);
                previous = weight;
            }
        }
    }

    /** 0xFFFF（未定义序号）必须恒可见；无语义序号（total<=0）或关闭生长时也必须整段可见。 */
    @Test
    public void undefinedOrderAndDisabledGrowthStayFullyVisible() {
        Assert.assertEquals("ORDER_UNDEFINED 必须恒可见",
                1.0F, ChainPreviewShaderMath.growthWeight(true, 0.5F, ChainPreviewMesh.APPEAR_ORDER_UNDEFINED, 256.0F), 0.0F);
        Assert.assertEquals("无目标总数时关闭生长比较（整段可见）",
                1.0F, ChainPreviewShaderMath.growthWeight(true, 0.5F, 3.0F, 0.0F), 0.0F);
        Assert.assertEquals("生长未启用时不得隐藏任何顶点",
                1.0F, ChainPreviewShaderMath.growthWeight(false, 0.0F, 200.0F, 256.0F), 0.0F);
        Assert.assertEquals("u>=1 时整段可见（GLSL 跳过比较）",
                1.0F, ChainPreviewShaderMath.growthWeight(true, 1.0F, 200.0F, 256.0F), 0.0F);
    }

    /** 判据边界：u 恰好跨过 (order+1)/total 时该顶点从 0 变为可见。 */
    @Test
    public void growthBoundaryMatchesRoundSemantics() {
        float total = 256.0F;
        // 边界语义（cell 式）：growth = clamp(u×total − order, 0, 1)，
        // 因此 u×total == order 时该顶点「刚进入」（growth 为 0），order−1 已经完全出现。
        // 取 u=0.5（二进制精确）⇒ u×total = 128：
        //   order=128 → 刚进入（0）；order=127 → 完全出现（1）；order=129 → 尚未出现（0）。
        float boundary = 0.5F;
        Assert.assertEquals("u×total == order 时刚好进入（growth=0）",
                0.0F, ChainPreviewShaderMath.growthWeight(true, boundary, 128.0F, total), 1.0e-6F);
        Assert.assertEquals("前一个序号必须已完全出现",
                1.0F, ChainPreviewShaderMath.growthWeight(true, boundary, 127.0F, total), 1.0e-6F);
        Assert.assertEquals("后一个序号必须尚未出现",
                0.0F, ChainPreviewShaderMath.growthWeight(true, boundary, 129.0F, total), 1.0e-6F);

        // 跨过边界：order=128 在 u 略高于 0.5 后开始可见
        Assert.assertEquals("略低于边界时仍未可见",
                0.0F, ChainPreviewShaderMath.growthWeight(true, Math.nextDown(boundary), 128.0F, total), 1.0e-6F);
        Assert.assertTrue("略高于边界后必须可见（growth 略大于 0）",
                ChainPreviewShaderMath.growthWeight(true, Math.nextUp(boundary), 128.0F, total) > 0.0F);

        // 与可见数规则交叉验证：u=0.5 时最大「完全出现」的序号是 127
        Assert.assertEquals("可见数语义必须与逐顶点式一致（ceil(u×total)−1）",
                127, ChainPreviewShaderMath.visibleOrderCount(boundary, total));
        Assert.assertEquals("u=0.25 时最大完全出现的序号是 63", 63,
                ChainPreviewShaderMath.visibleOrderCount(0.25F, total));
    }

    /** 用真实 mesh 的 aAux 驱动：u=0 全隐 → u=1 全显，且中间单调。 */
    @Test
    public void realMeshAuxDrivesMonotonicReveal() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
                java.util.Arrays.asList(
                        new ChainTarget(0, 0, 0), new ChainTarget(1, 0, 0), new ChainTarget(2, 0, 0),
                        new ChainTarget(2, 1, 0), new ChainTarget(2, 2, 0), new ChainTarget(2, 2, 1)),
                new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F));
        Assert.assertTrue("测试前提：mesh 必须带 aAux", mesh.isAuxAvailable());

        byte[] aux = mesh.auxArray();
        int vertexCount = mesh.getVertexFloatCount() / 3;
        float maxOrder = ChainPreviewShaderBackend.maxAppearOrder(aux, vertexCount);
        Assert.assertTrue("测试前提：mesh 必须带出现序号", maxOrder >= 0.0F);
        float totalTargets = maxOrder + 1.0F;

        int previousVisible = -1;
        for (int step = 0; step <= 200; step++) {
            float u = step / 200.0F;
            int visible = countVisibleInMesh(aux, vertexCount, u, totalTargets);
            Assert.assertTrue("真实 mesh：可见顶点数必须单调不减（u=" + u + "）", visible >= previousVisible);
            previousVisible = visible;
        }
        Assert.assertEquals("u=0 时真实 mesh 必须全隐", 0,
                countVisibleInMesh(aux, vertexCount, 0.0F, totalTargets));
        Assert.assertEquals("u=1 时真实 mesh 必须全显", vertexCount,
                countVisibleInMesh(aux, vertexCount, 1.0F, totalTargets));
    }

    /**
     * T13-D1：单目标链路（最大出现序号 = 0）必须仍然生长，不得被当成「关闭生长」而立即全显。
     *
     * <p>根因是拿 {@code maxAppearOrder == 0} 当关闭条件；正确语义是「序号总数」：
     * total = maxOrder + 1 = 1 &gt; 0 ⇒ 生长开启。</p>
     */
    @Test
    public void singleTargetChainStillGrows() {
        float total = 1.0F; // maxAppearOrder = 0 ⇒ 1 个目标
        Assert.assertEquals("u=0 时单目标也必须全隐", 0.0F,
                ChainPreviewShaderMath.growthWeight(true, 0.0F, 0.0F, total), 0.0F);
        Assert.assertEquals("u=0.25 时必须处于生长中（不是立即全显）", 0.25F,
                ChainPreviewShaderMath.growthWeight(true, 0.25F, 0.0F, total), 1.0e-6F);
        Assert.assertEquals("u=1 时单目标必须完全可见", 1.0F,
                ChainPreviewShaderMath.growthWeight(true, 1.0F, 0.0F, total), 0.0F);

        // 只有「无序号信息」（total<=0，对应 aux 缺失）才关闭生长比较。
        Assert.assertEquals("无语义序号信息时才整段可见", 1.0F,
                ChainPreviewShaderMath.growthWeight(true, 0.3F, 0.0F, 0.0F), 0.0F);
    }

    /**
     * T13-D3：取整规则必须与 GLSL 主路径一致。
     *
     * <p>GLSL 只让 {@code order < u × total} 的顶点可见，故最大可见序号 = {@code ceil(u×total)−1}；
     * 早先的 {@code floor(u×total+0.5)} 会多算一个（total=10、u=0.25 → 3 vs 2）。</p>
     */
    @Test
    public void visibleOrderRuleMatchesGlslSemantics() {
        Assert.assertEquals("total=10, u=0.25 → 最大可见序号 2", 2,
                ChainPreviewShaderMath.visibleOrderCount(0.25F, 10.0F));
        Assert.assertEquals("u=0 时无任何序号可见", -1,
                ChainPreviewShaderMath.visibleOrderCount(0.0F, 10.0F));
        Assert.assertEquals("u=1 时最大可见序号是 total-1（与逐顶点式一致）", 9,
                ChainPreviewShaderMath.visibleOrderCount(1.0F, 10.0F));
        Assert.assertEquals("无语义序号信息时视为全部可见", Integer.MAX_VALUE,
                ChainPreviewShaderMath.visibleOrderCount(0.5F, 0.0F));

        // 与逐顶点式交叉验证：visibleOrderCount 必须等于「growth>0 的最大 order」。
        for (float total : new float[] {1.0F, 2.0F, 7.0F, 10.0F, 256.0F}) {
            for (int step = 0; step <= 40; step++) {
                float u = step / 40.0F;
                int expected = -1;
                for (int order = 0; order < (int) total; order++) {
                    if (ChainPreviewShaderMath.growthWeight(true, u, (float) order, total) > 0.0F) {
                        expected = order;
                    }
                }
                Assert.assertEquals("total=" + total + " u=" + u + " 规则必须与逐顶点式一致",
                        expected, ChainPreviewShaderMath.visibleOrderCount(u, total));
            }
        }
    }

    // ------------------------------------------------------------------ B) 屏幕最小宽度

    /**
     * T13-D2：退化偏移不得放大——必须与 GLSL 的
     * {@code lateralMagnitude <= 0.02 × barThickness} 分支同形。
     *
     * <p>数值反例（审查给定）：halfThickness=0.0225、偏移=0.0004。
     * 0.02 × 0.0225 = 0.00045 &gt; 0.0004，属于格线残留而非条柱半厚度，
     * 放大它只会把顶点推出方块。</p>
     */
    @Test
    public void degenerateOffsetIsNotWidened() {
        float thickness = 0.045F;
        float offset = 0.0004F;
        float[] position = {offset, offset, offset};

        float[] output = ChainPreviewShaderMath.lateralClamp(
                1.0F, position[0], position[1], position[2], 0.001F, 1.0F, thickness);
        Assert.assertArrayEquals("低于 0.02×thickness 的退化偏移必须原样返回", position, output, 0.0F);

        // 略高于阈值时仍应放大（确认退化判据不是「一律不放大」）
        float[] above = {0.02F * thickness * 1.5F, 0.02F * thickness * 1.5F, 0.02F * thickness * 1.5F};
        float[] widened = ChainPreviewShaderMath.lateralClamp(
                1.0F, above[0], above[1], above[2], 0.001F, 1.0F, thickness);
        Assert.assertTrue("高于阈值必须放大", widened[0] > above[0]);
    }

    /** px=0（本轮默认）必须严格恒等：输出与输入逐值相等，不留任何残差。 */
    @Test
    public void minWidthDisabledIsExactIdentity() {
        float[][] probes = {
            {0.0F, 0.0F, 0.0F}, {0.0225F, 0.0225F, 0.0225F}, {-0.0225F, 0.5F, 0.2F},
            {0.5F, 0.5F, 0.5F}, {12.75F, -3.5F, 0.125F},
        };
        for (float[] probe : probes) {
            float[] output = ChainPreviewShaderMath.lateralClamp(
                    0.0F, probe[0], probe[1], probe[2], 900.0F, 1.0F);
            Assert.assertArrayEquals("px=0 必须逐值恒等", probe, output, 0.0F);
            float[] negative = ChainPreviewShaderMath.lateralClamp(
                    -1.0F, probe[0], probe[1], probe[2], 900.0F, 1.0F);
            Assert.assertArrayEquals("负值同样视为关闭（逐值恒等）", probe, negative, 0.0F);
        }
    }

    /** px&gt;0 且条柱已够宽（近处）时不得加粗：单向钳制。 */
    @Test
    public void minWidthNeverWidensAlreadyVisibleBars() {
        float[] position = {HALF_THICKNESS, HALF_THICKNESS, HALF_THICKNESS};
        // 像素/单位 = 900 → 宽 2×0.0225×900 = 40.5px，远超 1px 目标：不得加宽。
        float[] output = ChainPreviewShaderMath.lateralClamp(1.0F, position[0], position[1], position[2], 900.0F, 1.0F);
        Assert.assertArrayEquals("已够宽的条柱必须保持不变", position, output, 0.0F);
    }

    /** px&gt;0 且条柱为亚像素（远处）时必须加宽到目标像素宽度附近。 */
    @Test
    public void minWidthWidensSubpixelBarsTowardTarget() {
        float[] position = {HALF_THICKNESS, HALF_THICKNESS, HALF_THICKNESS};

        // 触上限的场景：2×0.0225×0.0863 ≈ 0.00388px，需要约 258 倍，被 64 倍上限截断。
        float[] capped = ChainPreviewShaderMath.lateralClamp(
                1.0F, position[0], position[1], position[2], 0.0863F, 1.0F);
        Assert.assertEquals("极远亚像素条柱必须被加宽到上限", HALF_THICKNESS * 64.0F, capped[0], 1.0e-6F);
        Assert.assertEquals("加宽只作用于横向轴（纵向轴保持原值）", position[1], capped[1], 0.0F);
        Assert.assertEquals(position[2], capped[2], 0.0F);

        // 未触上限的场景：2×0.0225×10 = 0.45px → widen ≈ 2.22，加宽后应正好达到 1px 目标。
        float[] modest = ChainPreviewShaderMath.lateralClamp(
                1.0F, position[0], position[1], position[2], 10.0F, 1.0F);
        float achievedPx = 2.0F * modest[0] * 10.0F;
        Assert.assertTrue("必须被加宽", modest[0] > position[0]);
        // 容差 1e-3：float 经 2×magnitude×ppu 往返会有 ~1ulp 误差（实测 0.99999994）。
        Assert.assertEquals("未被上限截断时必须达到 1px 目标", 1.0F, achievedPx, 1.0e-3F);

        float widen = modest[0] / position[0];
        Assert.assertTrue("放大倍数必须在 [1,64]", widen >= 1.0F && widen <= 64.0F + 1.0e-4F);
    }

    /**
     * T12/Lead 追加：轴向判据必须是「哪个轴的分量最小」，而不是拿最小值去和另两轴比。
     *
     * <p>该条件曾恒真（lateralMagnitude ≤ |y| 且 ≤ |z| 永远成立），导致位移永远沿 X 轴，
     * 远距时把条柱沿世界 X 推出方块（最大约 1.4 格），而「只断言加宽/不加宽」的测试会假通过。</p>
     */
    @Test
    public void minWidthWidensAlongTheSmallestMagnitudeAxis() {
        // verifier 给出的反例：|y|=3.9775 最小 ⇒ 必须沿 Y 轴加宽，而不是 X。
        float[] position = {12.0225F, 3.9775F, 7.0225F};
        float pixelPerUnit = 0.02F; // 极小 ⇒ 触 64 倍上限，位移显著、便于判轴
        float[] output = ChainPreviewShaderMath.lateralClamp(
                1.0F, position[0], position[1], position[2], pixelPerUnit, 1.0F);

        Assert.assertEquals("X 轴不是最小分量轴，必须保持原值", position[0], output[0], 0.0F);
        Assert.assertTrue("Y 轴是最小分量轴，必须被加宽", output[1] > position[1]);
        Assert.assertEquals("Z 轴必须保持原值", position[2], output[2], 0.0F);

        // 位移量必须等于 半厚度 × (widen-1)，且不超过 64 倍上限
        float delta = output[1] - position[1];
        Assert.assertTrue("位移必须为正", delta > 0.0F);
        Assert.assertTrue("位移不得超过 64 倍半厚度", delta <= 3.9775F * 63.0F + 1.0e-3F);
    }

    /**
     * 穷举网格：加宽轴必须恒等于「最小 |分量| 轴」，且只有该轴发生变化。
     *
     * <p>覆盖 X/Y/Z 三轴各为主导的情形，以及分量为 0、为负的边界。</p>
     */
    @Test
    public void minWidthAxisSelectionMatchesSmallestMagnitudeOnGrid() {
        float[][] probes = {
            {12.0225F, 3.9775F, 7.0225F},   // Y 主导
            {3.9775F, 12.0225F, 7.0225F},   // X 主导
            {12.0225F, 7.0225F, 3.9775F},   // Z 主导
            {0.0225F, 5.0F, 5.0F},          // X 主导（含极小值）
            {-0.0225F, -5.0F, -5.0F},       // 负坐标 X 主导
            {5.0F, -0.0225F, 5.0F},         // 负坐标 Y 主导
            {5.0F, 5.0F, -0.0225F},         // 负坐标 Z 主导
            {0.5F, 0.5F, 0.0225F},          // Z 主导（格线场景）
        };
        for (float[] probe : probes) {
            float pixelPerUnit = 0.02F;
            float[] output = ChainPreviewShaderMath.lateralClamp(
                    1.0F, probe[0], probe[1], probe[2], pixelPerUnit, 1.0F);

            int expectedAxis = smallestMagnitudeAxis(probe[0], probe[1], probe[2]);
            for (int axis = 0; axis < 3; axis++) {
                if (axis == expectedAxis) {
                    Assert.assertTrue("轴 " + axis + " 必须被加宽（probe="
                                    + probe[0] + "," + probe[1] + "," + probe[2] + "）",
                            Math.abs(output[axis]) > Math.abs(probe[axis]));
                } else {
                    Assert.assertEquals("轴 " + axis + " 不得变化（probe="
                                    + probe[0] + "," + probe[1] + "," + probe[2] + "）",
                            probe[axis], output[axis], 0.0F);
                }
            }
        }
    }

    /** 返回三轴中绝对分量最小的轴下标（0=X, 1=Y, 2=Z）。 */
    private static int smallestMagnitudeAxis(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (ax <= ay && ax <= az) {
            return 0;
        }
        return ay <= az ? 1 : 2;
    }

    /** 横向投影越小（正对视线）时加宽越保守，但不得突破 64 倍上限。 */
    @Test
    public void minWidthRespectsProjectionAndCap() {
        float[] position = {HALF_THICKNESS, HALF_THICKNESS, HALF_THICKNESS};
        float full = ChainPreviewShaderMath.lateralClamp(1.0F, position[0], position[1], position[2], 0.1F, 1.0F)[0];
        float partial = ChainPreviewShaderMath.lateralClamp(1.0F, position[0], position[1], position[2], 0.1F, 0.5F)[0];
        Assert.assertTrue("投影越短需要的放大越少", partial <= full);
        float capped = ChainPreviewShaderMath.lateralClamp(8.0F, position[0], position[1], position[2], 0.0001F, 1.0F)[0];
        Assert.assertEquals("必须被 64 倍上限截断", HALF_THICKNESS * 64.0F, capped, 1.0e-6F);
    }

    /**
     * Lead 裁定（方案 a）：参考模型必须与 GLSL 表达式在 u × order 网格上逐值一致。
     *
     * <p>网格密度：257 个 u 采样 × 每个采样遍历全部 256 个 order = 65792 格，
     * 远超 Lead 要求的 200 个采样点，且逐格比较（不是只比端点）。</p>
     *
     * <p>覆盖范围包含 u=0 与 u=1 两个端点：u=1 在 GLSL 里走「整段可见」出口
     * （根本不进入逐顶点比较），参考模型必须给出同一结论，否则整代条柱会在边界偶发隐藏。</p>
     */
    @Test
    public void growthModelMatchesGlslExpressionAcrossFullGrid() {
        float total = 256.0F;
        float[] progressSamples = buildProgressSamples();
        float worstError = 0.0F;
        long cells = 0L;

        for (float progress : progressSamples) {
            for (int order = 0; order < (int) total; order++) {
                float model = ChainPreviewShaderMath.growthWeight(true, progress, (float) order, total);
                float glsl = evaluateGlslGrowthPerVertex(progress, (float) order, total);
                worstError = Math.max(worstError, Math.abs(model - glsl));
                Assert.assertEquals("u=" + progress + " order=" + order + " 必须与 GLSL 同形",
                        glsl, model, 1.0e-5F);
                cells++;
            }
        }
        Assert.assertTrue("网格格数必须 >= 200，实际=" + cells, cells >= 200L);
        Assert.assertTrue("最大偏差必须远小于 1e-5，实际=" + worstError, worstError < 1.0e-5F);
        Assert.assertEquals("u 采样数必须 >= 200", 257, progressSamples.length);
    }

    /** u>=1 时 GLSL 与参考模型都必须走「整段可见」出口（不进入逐顶点比较）。 */
    @Test
    public void growthAtCompleteProgressIsFullyVisibleForEveryOrder() {
        float total = 256.0F;
        for (float order : new float[] {0.0F, 1.0F, 127.0F, 255.0F, 65535.0F}) {
            Assert.assertEquals("u=1 时 order=" + order + " 必须完全可见（GLSL 整段出口）",
                    1.0F, evaluateGlslGrowthPerVertex(1.0F, order, total), 0.0F);
            Assert.assertEquals("参考模型必须一致",
                    1.0F, ChainPreviewShaderMath.growthWeight(true, 1.0F, order, total), 0.0F);
        }
    }

    /**
     * Lead 要求的可见数单调断言：order=0..N 中 growth&gt;0 的个数随 u 单调不减，
     * 且 u=0 时为 0、u=1 时为 N；0xFFFF 视为已出现，不产生空洞。
     */
    @Test
    public void visibleCountIsMonotonicEndToEnd() {
        float total = 256.0F;
        int previous = -1;
        for (int step = 0; step <= 512; step++) {
            float u = step / 512.0F;
            int visible = 0;
            for (int order = 0; order < (int) total; order++) {
                if (ChainPreviewShaderMath.growthWeight(true, u, (float) order, total) > 0.0F) {
                    visible++;
                }
            }
            Assert.assertTrue("u=" + u + " 可见数必须单调不减", visible >= previous);
            previous = visible;
        }
        Assert.assertEquals("u=0 必须全隐", 0, countVisibleAt(0.0F, total));
        Assert.assertEquals("u=1 必须全显", (int) total, countVisibleAt(1.0F, total));

        // 0xFFFF 恒可见：不得因为序号未定义而在某进度下被隐藏
        for (int step = 0; step <= 32; step++) {
            float u = step / 32.0F;
            Assert.assertEquals("u=" + u + " 时未定义序号必须已出现（不产生空洞）",
                    1.0F, ChainPreviewShaderMath.growthWeight(true, u, 65535.0F, total), 0.0F);
        }
    }

    // ------------------------------------------------------------------ GLSL 同形断言

    /**
     * GLSL 必须真的把钳制结果用于投影。
     *
     * <p>此前 {@code gl_Position = ftransform()} 让 {@code displaced} 算完即丢，
     * 导致最小宽度在 shader 路径静默失效——这条断言是那次缺陷的回归锁。</p>
     */
    @Test
    public void glslProjectsTheDisplacedPosition() throws Exception {
        String body = methodBody(VERTEX_PATH, "void main(void)", "main");
        Assert.assertTrue("必须计算位移后的位置", body.contains("displaced"));
        Assert.assertTrue("必须对 displaced 做投影",
                body.contains("gl_ModelViewProjectionMatrix * vec4(displaced, 1.0)"));
        Assert.assertFalse("不得再用 ftransform()（它会忽略 displaced，使最小宽度失效）",
                body.contains("ftransform()"));
    }

    /** GLSL 的生长判据必须与参考模型同形（序号格之差 + 0xFFFF 放行 + u>=1 跳过）。 */
    @Test
    public void glslGrowthGuardMatchesReferenceModel() throws Exception {
        String body = methodBody(VERTEX_PATH, "void main(void)", "main");
        Assert.assertTrue("必须按序号格判定出现",
                body.contains("floor(min(appearOrder, uAppearSpan))"));
        Assert.assertTrue("判据必须是 u × 总数 − 序号格",
                body.contains("uAnimProgress * uAppearSpan - orderFloor"));
        Assert.assertTrue("0xFFFF 必须走「已出现」分支",
                body.contains("appearOrder < 65535.0"));
        Assert.assertTrue("u>=1 必须跳过 appearOrder 比较（整段绘制）",
                body.contains("uAnimProgress < 1.0"));
    }

    /** GLSL 的最小宽度必须由 uMinScreenWidthPx 门控，且 px<=0 时位移保持原样。 */
    @Test
    public void glslMinWidthIsGatedAndIdentityWhenDisabled() throws Exception {
        String body = methodBody(VERTEX_PATH, "void main(void)", "main");
        Assert.assertTrue("必须由 uMinScreenWidthPx > 0 门控", body.contains("uMinScreenWidthPx > 0.0"));
        Assert.assertTrue("位移初值必须是原始位置（px=0 恒等）", body.contains("vec3 displaced = aPos;"));
        Assert.assertTrue("必须使用视口像素换算", body.contains("uPixelScale"));
    }

    /** backend 必须把 u 与目标总数传进着色器，且 u>=1 时关闭逐顶点比较。 */
    @Test
    public void backendPassesProgressAndTotalTargets() throws Exception {
        String body = methodBody(BACKEND_PATH, "private void applyUniforms(", "applyUniforms");
        Assert.assertTrue("必须读 plan 的 animationU", body.contains("plan.getAnimationU()"));
        Assert.assertTrue("必须把目标总数（maxOrder + 1）传给着色器", body.contains("appearSpan + 1.0F"));
        Assert.assertTrue("u>=1 必须走整段可见分支", body.contains("ANIMATION_COMPLETE"));
    }

    // ------------------------------------------------------------------ 辅助

    /** 生成 u ∈ [0,1] 的稠密采样（含 0 与 1 两个端点）。 */
    private static float[] buildProgressSamples() {
        float[] samples = new float[257];
        for (int index = 0; index < samples.length; index++) {
            samples[index] = index / (float) (samples.length - 1);
        }
        return samples;
    }

    /** 直接镜像 GLSL 的逐顶点式（给定 order）。 */
    private static float evaluateGlslGrowthPerVertex(
            float uAnimProgress, float appearOrder, float uAppearSpan) {
        if (uAnimProgress >= 1.0F || !(uAppearSpan > 0.0F)) {
            return 1.0F;
        }
        if (appearOrder >= 65535.0F) {
            return 1.0F;
        }
        float orderFloor = (float) Math.floor(Math.min(appearOrder, uAppearSpan));
        float raw = uAnimProgress * uAppearSpan - orderFloor;
        return raw < 0.0F ? 0.0F : (raw > 1.0F ? 1.0F : raw);
    }

    private static int countVisibleAt(float u, float total) {
        int visible = 0;
        for (int order = 0; order < (int) total; order++) {
            if (ChainPreviewShaderMath.growthWeight(true, u, (float) order, total) > 0.0F) {
                visible++;
            }
        }
        return visible;
    }

    private static int countVisible(float u, float total) {
        int visible = 0;
        for (int order = 0; order < total; order++) {
            if (ChainPreviewShaderMath.growthWeight(true, u, order, total) > 0.0F) {
                visible++;
            }
        }
        return visible;
    }

    private static int countVisibleInMesh(byte[] aux, int vertexCount, float u, float totalTargets) {
        int visible = 0;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int offset = vertex * ChainPreviewMesh.AUX_BYTES_PER_VERTEX;
            int order = (aux[offset + 2] & 0xFF) | ((aux[offset + 3] & 0xFF) << 8);
            if (ChainPreviewShaderMath.growthWeight(true, u, order, totalTargets) > 0.0F) {
                visible++;
            }
        }
        return visible;
    }

    /** 抽取方法体（按花括号配平），用于「GLSL/Java 表达式同形」断言。 */
    private static String methodBody(String relativePath, String signature, String label) throws Exception {
        String code = stripComments(read(relativePath));
        int at = code.indexOf(signature);
        Assert.assertTrue("找不到 " + label + "（签名=" + signature + "）", at >= 0);
        int open = code.indexOf('{', at);
        Assert.assertTrue(label + " 缺函数体", open > at);
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char ch = code.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(at, index + 1);
                }
            }
        }
        Assert.fail(label + " 花括号不平衡");
        return "";
    }

    private static String read(String relativePath) throws Exception {
        Path direct = Paths.get(relativePath);
        if (!Files.isRegularFile(direct)) {
            Path dir = Paths.get("").toAbsolutePath();
            while (dir != null) {
                Path candidate = dir.resolve(relativePath);
                if (Files.isRegularFile(candidate)) {
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                }
                dir = dir.getParent();
            }
        }
        Assert.assertTrue("找不到文件: " + relativePath, Files.isRegularFile(direct));
        return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
    }

    private static String stripComments(String source) {
        return Glsl120StaticChecker.stripComments(
                source, "src", new ArrayList<Glsl120StaticChecker.Finding>());
    }

    /** 与 GLSL 无关但需要被引用的字符串比较工具（保持 import 有效）。 */
    static List<String> linesOf(String text) {
        return Collections.unmodifiableList(java.util.Arrays.asList(text.split("\\n")));
    }
}
