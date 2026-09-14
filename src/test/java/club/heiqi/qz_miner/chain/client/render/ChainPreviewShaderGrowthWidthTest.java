package club.heiqi.qz_miner.chain.client.render;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

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
 *       px&gt;0 只对亚像素条柱沿面法线单向加宽、**真的交付配置像素宽**，近处（已够宽）保持不变，
 *       位移受与真描边**共用**的世界空间预算约束（A1/A2/T52 修复）。</li>
 * </ol>
 *
 * <p><strong>不读 shader 源码做文本匹配</strong>：GLSL 表达式的形状不再被
 * {@code body.contains(...)} 钉住（重命名即误报、改系数却照样绿）。参考模型的数值形状在此逐值
 * 断言，GLSL 与参考模型的一致性由「真机验证 + shader 头部「实机验证记录」追加标记」承担——
 * 且<strong>注释改动本身不触发重验</strong>（否则加标记会形成死循环）。</p>
 */
public class ChainPreviewShaderGrowthWidthTest {

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
     * px=0 / px&lt;0 / px=NaN（本轮默认档）必须严格恒等：输出与输入逐值相等，不留任何残差。
     *
     * <p>断言打在 {@link ChainPreviewShaderMath#displaceVertex}（与 {@code preview.vert} 的
     * {@code main()} 同形）上：旧模型 {@code lateralClamp}「从 aPos 猜横向轴」已随 T51 删除，
     * 继续断言它等于保护一个真机不存在的实现。</p>
     */
    @Test
    public void minWidthDisabledIsExactIdentity() {
        float[][] probes = {
            {0.0F, 0.0F, 0.0F}, {0.0225F, 0.0225F, 0.0225F}, {-0.0225F, 0.5F, 0.2F},
            {0.5F, 0.5F, 0.5F}, {12.75F, -3.5F, 0.125F},
        };
        for (float[] probe : probes) {
            for (float px : new float[] {0.0F, -1.0F, Float.NaN}) {
                float[] output = ChainPreviewShaderMath.displaceVertex(
                    probe[0], probe[1], probe[2], 0.0F, 1.0F, 0.0F, 900.0F, px, 0.045F, 0.0F);
                Assert.assertArrayEquals("px<=0（含 NaN）必须逐值恒等", probe, output, 0.0F);
            }
        }
    }

    /** px&gt;0 且条柱已够宽（近处）时不得加粗：单向钳制。 */
    @Test
    public void minWidthNeverWidensAlreadyVisibleBars() {
        float[] position = {HALF_THICKNESS, HALF_THICKNESS, HALF_THICKNESS};
        // ppwu=900 → 投影宽 0.045×900 = 40.5px，远超 1px 目标：外扩量必须精确为 0。
        Assert.assertEquals("已够宽时外扩量必须精确为 0",
            0.0F, ChainPreviewShaderMath.minWidthWidenWorld(1.0F, 0.045F, 900.0F), 0.0F);
        float[] output = ChainPreviewShaderMath.displaceVertex(
            position[0], position[1], position[2], 0.0F, 1.0F, 0.0F, 900.0F, 1.0F, 0.045F, 0.0F);
        Assert.assertArrayEquals("已够宽的条柱必须保持不变", position, output, 0.0F);
    }

    /**
     * A1 回归锁：配置的最小宽度必须**真的交付**（修复前激活区恒为 minW/2）。
     *
     * <p>默认厚度 t=0.045、ppwu=24 ⇒ 原始投影宽 1.08px。修复前 2..8px 档分别只交付
     * 1.08 / 1.5 / 2.0 / 2.5 / 3.0 / 3.5 / 4.0px（Python 独立复算见
     * 工作站 temp/qz-miner-minwidth-a1a2-recheck.py）；修复后必须逐档等于配置值。</p>
     */
    @Test
    public void minWidthDeliversConfiguredWidthAfterThresholdFix() {
        float thickness = 0.045F;
        float pixelsPerWorldUnit = 24.0F;
        for (float minWidthPx : new float[] {2.0F, 3.0F, 4.0F, 5.0F, 6.0F, 7.0F, 8.0F}) {
            float widen = ChainPreviewShaderMath.minWidthWidenWorld(minWidthPx, thickness, pixelsPerWorldUnit);
            float delivered = (thickness + 2.0F * widen) * pixelsPerWorldUnit;
            Assert.assertEquals("配置 " + minWidthPx + "px 必须真的交付",
                minWidthPx, delivered, 1.0e-3F);
        }
        // 阈值以下（目标不高于原始投影宽）必须精确恒等：单向钳制不得反向缩窄。
        Assert.assertEquals("目标低于原始宽时必须精确恒等",
            0.0F, ChainPreviewShaderMath.minWidthWidenWorld(1.0F, thickness, pixelsPerWorldUnit), 0.0F);
        Assert.assertEquals("目标远低于原始宽时必须精确恒等",
            0.0F, ChainPreviewShaderMath.minWidthWidenWorld(0.5F, thickness, pixelsPerWorldUnit), 0.0F);
    }

    /**
     * A2 回归锁：世界上界必须与真描边**同一口径**（{@code max(0, 0.5 - t)}），极小像素密度不发散。
     *
     * <p>复算给定点：ppwu=0.01、t=0.045、minW=8 在无上界时单侧外扩约 400 格（本测试先证伪旧行为，
     * 再断言收敛到 0.455）。上界处到达半径 t/2 + cap = 0.4775 &le; 0.5，相邻条柱不粘连。</p>
     */
    @Test
    public void minWidthSaturatesAtTheSameCapAsOutline() {
        float thickness = 0.045F;
        float cap = ChainPreviewShaderMath.maxWidenWorld(thickness);
        Assert.assertEquals("上界口径必须是 max(0, 0.5 - t)", 0.455F, cap, 1.0e-7F);

        float unbounded = 0.5F * thickness * Math.max(0.0F, 8.0F / (thickness * 0.01F) - 1.0F);
        Assert.assertTrue("测试前提：未截断的位移必须远超上界（实际 " + unbounded + " 格）",
            unbounded > 100.0F);

        for (float pixelsPerWorldUnit : new float[] {1.0F, 0.1F, 0.01F, 1.0e-4F}) {
            float widen = ChainPreviewShaderMath.minWidthWidenWorld(8.0F, thickness, pixelsPerWorldUnit);
            Assert.assertFalse("极小像素密度不得产生 NaN（ppwu=" + pixelsPerWorldUnit + "）",
                Float.isNaN(widen));
            Assert.assertEquals("位移必须收敛到与描边同口径的上界（ppwu=" + pixelsPerWorldUnit + "）",
                cap, widen, 1.0e-6F);
            Assert.assertEquals("真描边在同一厚度下必须共用同一上界",
                cap, ChainPreviewShaderMath.outlineWidenWorld(1.0e6F, pixelsPerWorldUnit, thickness), 1.0e-6F);
        }

        float reach = thickness * 0.5F + cap;
        Assert.assertTrue("上界处不得越出自身方块（reach=" + reach + "）", reach <= 0.5F + 1.0e-6F);
        Assert.assertTrue("上界处相邻条柱不得粘连",
            ChainPreviewShaderMath.neighbourGap(thickness, cap) >= 0.0F);
    }

    /**
     * 位移方向恒为显式面法线 {@code aDirection}；零方向顶点恒等退化。
     *
     * <p替代已删除的 T13-D2「格线残留不放大」与「最小 |分量| 轴」判据：那两条针对的是
     * 「从 aPos 的绝对值猜横向轴」的旧实现，而 T51 起顶点身份 = (位置, 面)、位移恒沿该面法线，
     * 因此不存在「放大某个坐标分量把几何拉歪」的路径；退化保护由零方向守卫承担。</p>
     */
    @Test
    public void minWidthWidensAlongFaceDirectionOnly() {
        float[][] positions = {
            {0.0225F, 0.0225F, 0.0225F},
            {12.0225F, 3.9775F, 7.0225F},
            {-0.0225F, -5.0F, 5.0F},
        };
        float[][] directions = {
            {1.0F, 0.0F, 0.0F}, {-1.0F, 0.0F, 0.0F},
            {0.0F, 1.0F, 0.0F}, {0.0F, -1.0F, 0.0F},
            {0.0F, 0.0F, 1.0F}, {0.0F, 0.0F, -1.0F},
        };
        float expected = ChainPreviewShaderMath.minWidthWidenWorld(1.0F, 0.045F, 0.02F);
        Assert.assertTrue("测试前提：该参数下必须产生正位移", expected > 0.0F);
        for (float[] position : positions) {
            for (float[] direction : directions) {
                float[] after = ChainPreviewShaderMath.displaceVertex(
                    position[0], position[1], position[2],
                    direction[0], direction[1], direction[2], 0.02F, 1.0F, 0.045F, 0.0F);
                for (int axis = 0; axis < 3; axis++) {
                    Assert.assertEquals("位移必须逐轴等于 direction × 外扩量（轴 " + axis + "）",
                        position[axis] + direction[axis] * expected, after[axis], 1.0e-6F);
                }
            }
            float[] identity = ChainPreviewShaderMath.displaceVertex(
                position[0], position[1], position[2], 0.0F, 0.0F, 0.0F, 0.02F, 1.0F, 0.045F, 0.0F);
            Assert.assertArrayEquals("零方向顶点必须逐值恒等（防御性守卫）", position, identity, 0.0F);
        }
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

    // ------------------------------------------------------------------ 后端接线（Java 源面）

    /** backend 必须把 u 与目标总数传进着色器，且 u>=1 时关闭逐顶点比较。 */
    @Test
    public void backendPassesProgressAndTotalTargets() throws Exception {
        String body = methodBody(BACKEND_PATH, "private boolean applyUniforms(", "applyUniforms");
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
}
