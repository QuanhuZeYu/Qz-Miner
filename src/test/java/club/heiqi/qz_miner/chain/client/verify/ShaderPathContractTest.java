package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewRenderBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderBackend;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderMath;

/**
 * T7/T12 shader 路径独立契约探针（接口冻结 §F/§G + 波次 2 cell 式裁定）。
 *
 * <p>只依赖当前公开 API：像素尺度、quadratic 距离淡出、顶点距离、最小宽度位移
 * （px<=0 严格恒等 / px>0 沿面法线单向加宽 / 交付配置像素宽 / 近处不变 / 单调 /
 * 与真描边同口径的世界上界）、逐波生长 cell 式
 * （u=0 全隐、u=1 全显、随 u 单调不减、随 order 单调不增、0xFFFF 恒可见、独立模型逐值交叉验证）、
 * visibleOrderCount 取整、字节还原与丢弃阈值，以及无 GL 上下文时 shader 后端的回退契约。</p>
 */
public class ShaderPathContractTest {

    @Test
    public void pixelScaleMatchesIndependentProjectionFormula() {
        Assert.assertEquals(540.0F, ChainPreviewShaderMath.pixelScale(1.0F, 1080), 0.0001F);
        Assert.assertEquals(1000.0F, ChainPreviewShaderMath.pixelScale(2.5F, 800), 0.0001F);
        Assert.assertEquals(75.0F, ChainPreviewShaderMath.pixelScale(-1.5F, 100), 0.0001F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.pixelScale(Float.NaN, 1080), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.pixelScale(1.0F, 0), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.pixelScale(1.0F, -10), 0.0F);

        float[] projections = {0.5F, 1.0F, 1.732F, 2.5F};
        int[] heights = {480, 720, 1080, 1440};
        for (float projection : projections) {
            for (int height : heights) {
                float expected = Math.abs(projection) * (float) height * 0.5F;
                Assert.assertEquals(
                    "projection=" + projection + " height=" + height,
                    expected,
                    ChainPreviewShaderMath.pixelScale(projection, height),
                    0.0001F);
            }
        }
    }

    @Test
    public void fadeAlphaMatchesCpuQuadraticCurve() {
        float[] distances = {0.0F, 1.9F, 2.0F, 2.5F, 3.0F, 4.0F, 5.0F, 5.9F, 6.0F, 10.0F};
        for (float distance : distances) {
            float expected = independentAlpha(distance, 2.0F, 6.0F, 0.78F, 0.15F);
            float actual = ChainPreviewShaderMath.fadeAlpha(distance, 2.0F, 6.0F, 0.78F, 0.15F);
            Assert.assertEquals("d=" + distance, expected, actual, 0.000001F);
        }
        Assert.assertEquals(0.78F, ChainPreviewShaderMath.fadeAlpha(0.0F, 2.0F, 6.0F, 0.78F, 0.15F), 0.0F);
        Assert.assertEquals(0.15F, ChainPreviewShaderMath.fadeAlpha(99.0F, 2.0F, 6.0F, 0.78F, 0.15F), 0.0F);
        Assert.assertEquals(
            0.622499942779541F,
            ChainPreviewShaderMath.fadeAlpha(4.0F, 2.0F, 6.0F, 0.78F, 0.15F),
            0.000001F);
    }

    @Test
    public void vertexDistanceUsesWorldOriginAndCamera() {
        Assert.assertEquals(
            0.0F,
            ChainPreviewShaderMath.vertexDistance(10.0D, 20.0D, 30.0D, 0.0F, 0.0F, 0.0F, 10.0D, 20.0D, 30.0D),
            0.0F);
        Assert.assertEquals(
            5.0F,
            ChainPreviewShaderMath.vertexDistance(0.0D, 0.0D, 0.0D, 3.0F, 4.0F, 0.0F, 0.0D, 0.0D, 0.0D),
            0.0001F);
        Assert.assertEquals(
            0.8660254F,
            ChainPreviewShaderMath.vertexDistance(0.0D, 0.0D, 0.0D, 0.5F, 0.5F, 0.5F, 0.0D, 0.0D, 0.0D),
            0.0001F);
        Assert.assertEquals(
            1.0F,
            ChainPreviewShaderMath.vertexDistance(100.0D, 64.0D, -50.0D, 0.0F, 0.0F, 0.0F, 100.0D, 64.0D, -51.0D),
            0.0001F);
    }

    /**
     * 屏幕最小宽度：px&lt;=0 严格恒等、px&gt;0 沿面法线单向加宽并**真的交付**配置像素宽。
     *
     * <p>T51 后真实位移只认 aDirection（每顶点面法线），不再从 aPos 猜横向轴；本测试打在
     * {@link ChainPreviewShaderMath#displaceVertex} 上——它与 {@code preview.vert} 的 main()
     * 同序同式，因此「测试保护的东西 = 真机跑的东西」。</p>
     *
     * <p>A1 修复前这里必须是红的：旧式用 {@code 2 × t × ppwu} 当基准，激活阈值翻倍、位移减半，
     * 交付宽恒为 minW/2（8px 档只交付 4px）。</p>
     */
    @Test
    public void minWidthIsIdentityAtZeroAndOneWayWhenPositive() {
        float[][] samples = {
            {0.0225F, 0.0225F, 0.0225F},
            {-1.0225F, 0.0225F, 0.0225F},
            {12.0225F, 3.9775F, 7.0225F},
            {0.0F, 0.0F, 0.0F},
            {100.5F, -64.25F, 7.75F},
        };
        for (float[] sample : samples) {
            for (float px : new float[] {0.0F, -3.0F, Float.NaN}) {
                float[] identity = ChainPreviewShaderMath.displaceVertex(
                    sample[0], sample[1], sample[2], 0.0F, 1.0F, 0.0F, 10.0F, px, 0.045F, 0.0F);
                Assert.assertArrayEquals("px<=0（或 NaN）必须逐值恒等", sample, identity, 0.0F);
            }
        }

        // t=0.045、ppwu=4 ⇒ 原始投影宽 0.18px（亚像素），1px 目标未触世界上界 ⇒ 必须精确达标。
        float[] far = ChainPreviewShaderMath.displaceVertex(
            12.0225F, 3.9775F, 7.0225F, 0.0F, 1.0F, 0.0F, 4.0F, 1.0F, 0.045F, 0.0F);
        Assert.assertEquals("非方向分量不得改动(x)", 12.0225F, far[0], 0.0F);
        Assert.assertEquals("非方向分量不得改动(z)", 7.0225F, far[2], 0.0F);
        Assert.assertTrue("方向轴必须被加宽：" + far[1], far[1] > 3.9775F);
        Assert.assertEquals("交付像素宽必须等于配置值（(t + 2d) × ppwu）",
            1.0F, (0.045F + 2.0F * (far[1] - 3.9775F)) * 4.0F, 1.0e-4F);

        // 近距（已足够宽）→ 恒等：单向钳制不得加粗近处。
        float[] near = ChainPreviewShaderMath.displaceVertex(
            12.0225F, 3.9775F, 7.0225F, 0.0F, 1.0F, 0.0F, 200.0F, 1.0F, 0.045F, 0.0F);
        Assert.assertEquals("近处不得加宽(1)", 3.9775F, near[1], 0.0F);

        // 世界上界（A2）：ppwu 极小时位移必须收敛到与真描边同口径的 max(0, 0.5 - t)。
        float cap = ChainPreviewShaderMath.maxWidenWorld(0.045F);
        float[] capped = ChainPreviewShaderMath.displaceVertex(
            12.0225F, 3.9775F, 7.0225F, 0.0F, 1.0F, 0.0F, 0.01F, 8.0F, 0.045F, 0.0F);
        Assert.assertEquals("极小像素密度必须收敛到上界", 3.9775F + cap, capped[1], 1.0e-4F);
        Assert.assertFalse("上界不得产生 NaN", Float.isNaN(capped[1]));

        // 单调：px 越大，沿方向的偏移越大（同深度）。
        float previous = -1.0F;
        for (float px : new float[] {0.0F, 0.5F, 1.0F, 4.0F, 8.0F}) {
            float[] displaced = ChainPreviewShaderMath.displaceVertex(
                12.0225F, 3.9775F, 7.0225F, 0.0F, 1.0F, 0.0F, 0.05F, px, 0.045F, 0.0F);
            Assert.assertTrue("位移随 px 单调不减：" + previous + "->" + displaced[1],
                displaced[1] >= previous);
            previous = displaced[1];
        }
    }

    /**
     * 位移方向恒为显式面法线 aDirection；六个主轴方向逐轴相等，零方向恒等退化。
     *
     * <p>替换旧断言（「最小 |分量| 轴被加宽」）：那条实现已随 T51 删除，其模型即便继续绿灯也
     * 保护不了真机行为——本测试改打在真实位移式上。</p>
     */
    @Test
    public void minWidthWidensAlongTheExplicitFaceDirectionOnly() {
        float[][] directions = {
            {1.0F, 0.0F, 0.0F}, {-1.0F, 0.0F, 0.0F},
            {0.0F, 1.0F, 0.0F}, {0.0F, -1.0F, 0.0F},
            {0.0F, 0.0F, 1.0F}, {0.0F, 0.0F, -1.0F},
        };
        float expected = ChainPreviewShaderMath.minWidthWidenWorld(1.0F, 0.045F, 0.05F);
        Assert.assertTrue("测试前提：该参数下必须产生正位移（实际 " + expected + "）", expected > 0.0F);
        for (float[] direction : directions) {
            float[] after = ChainPreviewShaderMath.displaceVertex(
                0.0225F, 0.0225F, 0.0225F,
                direction[0], direction[1], direction[2], 0.05F, 1.0F, 0.045F, 0.0F);
            for (int axis = 0; axis < 3; axis++) {
                Assert.assertEquals("位移必须逐轴等于 direction × 外扩量（轴 " + axis + "）",
                    0.0225F + direction[axis] * expected, after[axis], 1.0e-6F);
            }
        }
        float[] zeroDirection = ChainPreviewShaderMath.displaceVertex(
            0.0225F, 0.0225F, 0.0225F, 0.0F, 0.0F, 0.0F, 0.05F, 1.0F, 0.045F, 0.0F);
        Assert.assertArrayEquals("零方向顶点必须逐值恒等退化",
            new float[] {0.0225F, 0.0225F, 0.0225F}, zeroDirection, 0.0F);
    }

    @Test
    public void growthWeightUsesOrderCellSemantics() {
        Assert.assertEquals(
            "未启用生长权重恒为 1",
            1.0F,
            ChainPreviewShaderMath.growthWeight(false, 0.3F, 0.0F, 64.0F),
            0.0F);
        Assert.assertEquals(
            "无目标数信息恒为 1",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.3F, 0.0F, 0.0F),
            0.0F);
        Assert.assertEquals(
            "未定义序号恒可见",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.0F, 65535.0F, 64.0F),
            0.0F);
        Assert.assertEquals(
            "u=0 时 order=0 也必须全隐",
            0.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.0F, 0.0F, 64.0F),
            0.0F);
        Assert.assertEquals(
            "u=1 整段可见",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 1.0F, 63.0F, 64.0F),
            0.0F);
        Assert.assertEquals(
            "u=0.5/order=0 已满格",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.5F, 0.0F, 64.0F),
            0.0F);
        Assert.assertEquals(
            "u=0.5/order=31 恰好满格",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.5F, 31.0F, 64.0F),
            0.0F);
        Assert.assertEquals(
            "u=0.5/order=32 恰好未出现",
            0.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.5F, 32.0F, 64.0F),
            0.0F);
        Assert.assertEquals(
            "u=0.5/order=63 未出现",
            0.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.5F, 63.0F, 64.0F),
            0.0F);
        // 过渡由「u 的小数格」驱动：order=32 的顶点在 u=(32+δ)/64 时为 δ。
        Assert.assertEquals(
            "u 处于该顶点序号格 1/4 处 → 权重 0.25",
            0.25F,
            ChainPreviewShaderMath.growthWeight(true, 32.25F / 64.0F, 32.0F, 64.0F),
            0.000001F);
        Assert.assertEquals(
            "u 处于该顶点序号格 1/2 处 → 权重 0.5",
            0.5F,
            ChainPreviewShaderMath.growthWeight(true, 32.5F / 64.0F, 32.0F, 64.0F),
            0.000001F);
        Assert.assertEquals(
            "order 的小数部分不参与过渡（floor 语义）",
            0.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.5F, 32.25F, 64.0F),
            0.0F);

        float previous = -1.0F;
        for (int step = 0; step <= 20; step++) {
            float u = step / 20.0F;
            float weight = ChainPreviewShaderMath.growthWeight(true, u, 17.0F, 64.0F);
            Assert.assertTrue("随 u 单调不减", weight >= previous - 0.000001F);
            Assert.assertTrue("权重在 [0,1]", weight >= 0.0F && weight <= 1.0F);
            previous = weight;
        }
        float previousOrderWeight = 2.0F;
        for (int order = 0; order < 64; order++) {
            float weight = ChainPreviewShaderMath.growthWeight(true, 0.5F, (float) order, 64.0F);
            Assert.assertTrue("随 order 单调不增", weight <= previousOrderWeight + 0.000001F);
            previousOrderWeight = weight;
        }
    }

    @Test
    public void growthWeightMatchesIndependentCellModel() {
        int[] totals = {1, 2, 7, 64, 300, 4096};
        for (int total : totals) {
            int stride = Math.max(1, total / 17);
            for (int step = 0; step <= 20; step++) {
                float u = step / 20.0F;
                for (int order = 0; order < total; order += stride) {
                    float expected = independentCellGrowth(u, (float) order, (float) total);
                    float actual = ChainPreviewShaderMath.growthWeight(true, u, (float) order, (float) total);
                    Assert.assertEquals(
                        "u=" + u + " order=" + order + " total=" + total,
                        expected,
                        actual,
                        0.000001F);
                }
            }
        }
    }

    @Test
    public void visibleOrderCountRoundsAndHandlesMissingTotals() {
        // T13-D3：最大可见序号 = ceil(u × total) − 1（u=0 无可见 → -1；u>=1 → total）。
        Assert.assertEquals(-1, ChainPreviewShaderMath.visibleOrderCount(0.0F, 64.0F));
        Assert.assertEquals(31, ChainPreviewShaderMath.visibleOrderCount(0.5F, 64.0F));
        Assert.assertEquals(
            "T13-D3：u=1 时最大可见序号为 total-1",
            63,
            ChainPreviewShaderMath.visibleOrderCount(1.0F, 64.0F));
        Assert.assertEquals(32, ChainPreviewShaderMath.visibleOrderCount(0.5F, 65.0F));
        Assert.assertEquals(0, ChainPreviewShaderMath.visibleOrderCount(0.01F, 64.0F));
        Assert.assertEquals(
            "无目标数信息返回哨兵",
            Integer.MAX_VALUE,
            ChainPreviewShaderMath.visibleOrderCount(0.5F, 0.0F));
        int previous = -1;
        for (int step = 0; step <= 20; step++) {
            int count = ChainPreviewShaderMath.visibleOrderCount(step / 20.0F, 128.0F);
            Assert.assertTrue("可见序号数随 u 单调不减", count >= previous);
            previous = count;
        }

        // 与逐顶点权重严格一致：最大 order 满足 growthWeight > 0，且下一个 order 必须为 0。
        int total = 64;
        for (int step = 1; step < 20; step++) {
            float u = step / 20.0F;
            int lastVisible = -1;
            for (int order = 0; order < total; order++) {
                if (ChainPreviewShaderMath.growthWeight(true, u, (float) order, (float) total) > 0.0F) {
                    lastVisible = order;
                }
            }
            int reported = ChainPreviewShaderMath.visibleOrderCount(u, (float) total);
            Assert.assertEquals(
                "u=" + u + " 诊断口径必须与逐顶点权重一致",
                lastVisible,
                reported);
            if (reported + 1 < total) {
                Assert.assertEquals(
                    "已出现边界后一个序号必须完全不可见",
                    0.0F,
                    ChainPreviewShaderMath.growthWeight(true, u, (float) (reported + 1), (float) total),
                    0.0F);
            }
        }
    }

    @Test
    public void channelDecodeAndDiscardThresholdMatchByteSemantics() {
        Assert.assertEquals(0, ChainPreviewShaderMath.unquantizeChannel(0.0F));
        Assert.assertEquals(255, ChainPreviewShaderMath.unquantizeChannel(1.0F));
        Assert.assertEquals(1, ChainPreviewShaderMath.unquantizeChannel(1.0F / 255.0F));
        Assert.assertEquals(128, ChainPreviewShaderMath.unquantizeChannel(128.0F / 255.0F));
        Assert.assertEquals(255, ChainPreviewShaderMath.unquantizeChannel(9.0F));
        Assert.assertEquals(0, ChainPreviewShaderMath.unquantizeChannel(-1.0F));
        Assert.assertTrue(ChainPreviewShaderMath.isFragmented(0.0F));
        Assert.assertTrue(ChainPreviewShaderMath.isFragmented(0.0039F));
        Assert.assertFalse(ChainPreviewShaderMath.isFragmented(0.00391F));
        Assert.assertFalse(ChainPreviewShaderMath.isFragmented(1.0F));
    }

    @Test
    public void shaderBackendFailsSafelyWithoutGlContext() {
        // §C：ensureReady 失败返回 false 且不抛；§G：编译/链接失败当帧起回退 legacy，不每帧重试。
        ChainPreviewRenderBackend backend = ChainPreviewShaderBackend.create();
        Assert.assertNotNull(backend);
        Assert.assertEquals("shader", backend.id());
        Assert.assertFalse("shader 后端不得使用 CPU 颜色流", backend.usesCpuColors());

        Assert.assertFalse("无 GL 上下文时必须返回 false 且不抛", backend.ensureReady());
        Assert.assertFalse("失败必须被锁存，不得每帧重试", backend.ensureReady());
        Assert.assertNotNull(backend.describe());
        Assert.assertTrue("诊断文本不得为空", backend.describe().length() > 0);

        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            VerifyShapes.line(2),
            new ChainPreviewMeshBuilder.VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F));
        backend.uploadTopology(mesh);
        backend.uploadTopology(ChainPreviewMesh.EMPTY);
        Assert.assertFalse("未就绪时颜色上传必须返回 false", backend.uploadColors(mesh));
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh, 0, mesh.getIndexCount(), null, null,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
            0, 0, 0, 0L, 0L);
        backend.draw(plan);
        backend.draw(null);
        backend.dispose();
        backend.dispose();
        Assert.assertFalse("释放后再探测仍不得抛", backend.ensureReady());
        Assert.assertTrue(backend.describe().length() > 0);
    }

    /** 独立复算：CPU 端 quadratic 距离淡出。 */
    private static float independentAlpha(
            float distance, float fadeStart, float fadeEnd, float maxAlpha, float minAlpha) {
        if (distance <= fadeStart) {
            return maxAlpha;
        }
        if (distance >= fadeEnd) {
            return minAlpha;
        }
        float normalized = (distance - fadeStart) / (fadeEnd - fadeStart);
        float squared = normalized * normalized;
        return maxAlpha - (maxAlpha - minAlpha) * squared;
    }

    /** 独立复算：cell 式逐波权重（与 GLSL 逐式同形，但由 verifier 独立写出）。 */
    private static float independentCellGrowth(float u, float order, float totalTargets) {
        if (totalTargets <= 0.0F) {
            return 1.0F;
        }
        if (u <= 0.0F) {
            return 0.0F;
        }
        if (u >= 1.0F) {
            return 1.0F;
        }
        float orderFloor = (float) Math.floor(Math.min(order, totalTargets));
        float value = u * totalTargets - orderFloor;
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }
}
