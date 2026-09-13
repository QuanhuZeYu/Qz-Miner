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
 * T7 shader 路径独立契约探针（接口冻结 §F/§G）。
 *
 * <p>覆盖两件不需要 GL 的事：a) 与 GLSL 同形的数值函数（像素尺度/距离淡出/最小宽度放大/
 * 逐波权重/字节还原/丢弃阈值）；b) 无 GL 上下文时 shader 后端必须「失败返回 false 且不抛」
 * 的回退契约（§C ensureReady 语义 + §G 编译链接失败当帧回退）。</p>
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

    @Test
    public void lateralWidenOnlyGrowsAndCapsAtSixtyFour() {
        Assert.assertEquals(
            "关闭最小宽度必须恒为 1",
            1.0F,
            ChainPreviewShaderMath.lateralWiden(0.0F, 0.0225F, 100.0F, 1.0F),
            0.0F);
        Assert.assertEquals(
            "无横向偏移必须恒为 1",
            1.0F,
            ChainPreviewShaderMath.lateralWiden(1.0F, 0.0F, 100.0F, 1.0F),
            0.0F);
        Assert.assertEquals(
            "已足够宽时不得放大",
            1.0F,
            ChainPreviewShaderMath.lateralWiden(1.0F, 0.0225F, 100.0F, 1.0F),
            0.0F);
        float widened = ChainPreviewShaderMath.lateralWiden(1.0F, 0.0225F, 10.0F, 1.0F);
        Assert.assertEquals(1.0F / 0.45F, widened, 0.001F);
        Assert.assertTrue("放大倍数恒 >= 1", widened >= 1.0F);
        Assert.assertEquals(
            "放大上限 64",
            64.0F,
            ChainPreviewShaderMath.lateralWiden(8.0F, 0.0001F, 1.0F, 1.0F),
            0.0F);
        // 横向投影被钳到下限 0.05 时，投影宽度变小 → 放大倍数变大（0.225px → 1/0.225）。
        float clampedProjection = ChainPreviewShaderMath.lateralWiden(1.0F, 0.0225F, 100.0F, 0.0F);
        Assert.assertEquals("横向投影下限 0.05 生效", 1.0F / 0.225F, clampedProjection, 0.001F);
    }

    @Test
    public void growthWeightIsHalfWhenProgressReachesVertexOrder() {
        Assert.assertEquals(
            "未启用生长权重恒为 1",
            1.0F,
            ChainPreviewShaderMath.growthWeight(false, 0.3F, 0.5F, 0.1F),
            0.0F);
        Assert.assertEquals(
            "过渡半宽 0 等价关闭",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.3F, 0.5F, 0.0F),
            0.0F);
        Assert.assertEquals(
            "appearOrder == progress 时正好半可见",
            0.5F,
            ChainPreviewShaderMath.growthWeight(true, 0.5F, 0.5F, 0.1F),
            0.000001F);
        Assert.assertEquals(
            "进度领先一个半宽必须完全可见",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.6F, 0.5F, 0.1F),
            0.000001F);
        Assert.assertEquals(
            "进度落后一个半宽必须完全不可见",
            0.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.4F, 0.5F, 0.1F),
            0.000001F);
        float previous = -1.0F;
        for (int step = 0; step <= 20; step++) {
            float progress = step / 20.0F;
            float weight = ChainPreviewShaderMath.growthWeight(true, progress, 0.4F, 0.2F);
            Assert.assertTrue("权重必须单调不减", weight >= previous - 0.000001F);
            Assert.assertTrue("权重必须在 [0,1]", weight >= 0.0F && weight <= 1.0F);
            previous = weight;
        }
    }

    @Test
    public void growthWeightTreatsUndefinedOrderAsVisible() {
        Assert.assertEquals(
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.0F, 65535.0F, 100.0F, 0.1F),
            0.0F);
        Assert.assertEquals(
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.0F, 0.0F, 0.0F, 0.1F),
            0.0F);
        // 序号归一化被钳制到 1；进度 1 时权重为半可见（公式：进度恰好到达序号 → 0.5），
        // 对应 GLSL 的 uAnimProgress >= 1 分支由调用方传 growthEnabled=false（backend 已如此分支）。
        Assert.assertEquals(
            "最大序号的顶点在进度 1 时为半可见（由调用方用 enabled=false 关闭）",
            0.5F,
            ChainPreviewShaderMath.growthWeight(true, 1.0F, 500.0F, 10.0F, 0.1F),
            0.000001F);
        Assert.assertEquals(
            "进度领先一个半宽后必须完全可见",
            1.0F,
            ChainPreviewShaderMath.growthWeight(true, 0.9F, 0.8F, 0.1F),
            0.000001F);
        Assert.assertEquals(
            "关闭生长标志必须整段可见（backend 在 animationU>=1 时走此分支）",
            1.0F,
            ChainPreviewShaderMath.growthWeight(false, 0.0F, 0.0F, 0.1F),
            0.0F);
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

    /** 独立复算：CPU 端 quadratic 距离淡出（与 DrawPlanContractTest 同一独立实现）。 */
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
}
