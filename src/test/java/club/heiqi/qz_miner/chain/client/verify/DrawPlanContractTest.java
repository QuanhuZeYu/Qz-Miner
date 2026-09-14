package club.heiqi.qz_miner.chain.client.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan.DepthChannel;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan.Visuals;
import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * T7 draw plan 独立契约探针（预览渲染契约 draw plan 契约 + Lead 裁定 C + origin 回归裁定）。
 *
 * <p>口径：plan 是纯数据 + 纯函数，可在纯 JVM 断言；origin 必须等于 mesh origin（renderer 据此平移）；
 * wave 的 legacy 路径本轮为整体绘制，shader 走逐顶点 appearOrder 比较，不假定索引有序。</p>
 */
public class DrawPlanContractTest {

    private static final int MASK = ChainPreviewDrawPlan.SEMANTIC_MASK_ALL;
    private static final float ALPHA_AT_DISTANCE_4 = 0.622499942779541F;
    private static final VisualParameters BUILDER_VISUALS =
        new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F);

    @Test
    public void planOriginFollowsMeshOrigin() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh far = builder.build(VerifyShapes.single(100, 64, -50), BUILDER_VISUALS);
        Assert.assertEquals(100, far.getOriginX());
        ChainPreviewDrawPlan farPlan = derive(far, 0, far.getIndexCount(), null, null, 0L, 0L);
        Assert.assertEquals("plan.origin 必须等于 mesh.origin", far.getOriginX(), farPlan.getOriginX());
        Assert.assertEquals(far.getOriginY(), farPlan.getOriginY());
        Assert.assertEquals(far.getOriginZ(), farPlan.getOriginZ());

        List<ChainTarget> ordered = new ArrayList<ChainTarget>();
        ordered.add(new ChainTarget(5, 0, 0));
        ordered.add(new ChainTarget(0, 0, 0));
        // 72fd97e 起公共 build 按生产快照序（最新→最早）解释输入：翻转为时间序后最早目标 = (5,0,0)。
        ChainPreviewMesh offsetMesh = builder.build(VerifyFeeds.snapshot(ordered), BUILDER_VISUALS);
        Assert.assertEquals(5, offsetMesh.getOriginX());
        ChainPreviewDrawPlan offsetPlan = derive(offsetMesh, 0, offsetMesh.getIndexCount(), null, null, 0L, 0L);
        Assert.assertEquals("origin 取迭代序首个唯一目标", 5, offsetPlan.getOriginX());
        Assert.assertEquals(0, offsetPlan.getOriginY());
        Assert.assertEquals(0, offsetPlan.getOriginZ());
    }

    @Test
    public void nullMeshYieldsEmptyPlanAndClampsCounters() {
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            null, 0, 100, null, null, MASK, 0, 0, 0, -5L, -7L);
        Assert.assertEquals(0, plan.getIndexOffset());
        Assert.assertEquals(0, plan.getIndexCount());
        Assert.assertEquals(0, plan.getQuadCount());
        Assert.assertEquals(0, plan.getVertexCount());
        Assert.assertEquals(0L, plan.getRebuilds());
        Assert.assertEquals(0L, plan.getUploads());
        Assert.assertEquals(0, plan.getVisibleIndexCount());
        Assert.assertFalse(plan.isAnimationEnabled());
        Assert.assertEquals(0, plan.getWaveVisible());
        Assert.assertFalse(plan.isTruncated());
        Assert.assertNotNull(plan.getVisuals());
        Assert.assertFalse(plan.getVisuals().getBarThickness() <= 0.0F);
    }

    @Test
    public void deriveClampsIndexRangeToMeshCapacity() {
        ChainPreviewMesh mesh = fixedMesh();
        int meshIndices = mesh.getIndexCount();
        Assert.assertTrue(meshIndices > 0);

        ChainPreviewDrawPlan negative = ChainPreviewDrawPlan.derive(
            mesh, -5, Integer.MAX_VALUE, null, null, MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(0, negative.getIndexOffset());
        Assert.assertEquals(meshIndices, negative.getIndexCount());
        Assert.assertEquals(meshIndices / 4, negative.getQuadCount());

        ChainPreviewDrawPlan beyond = ChainPreviewDrawPlan.derive(
            mesh, meshIndices + 10, 5, null, null, MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(meshIndices, beyond.getIndexOffset());
        Assert.assertEquals(0, beyond.getIndexCount());

        ChainPreviewDrawPlan partial = ChainPreviewDrawPlan.derive(
            mesh, 10, 999999, null, null, MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(10, partial.getIndexOffset());
        Assert.assertEquals(meshIndices - 10, partial.getIndexCount());
    }

    @Test
    public void legacyWaveDegradationDrawsWholeRange() {
        ChainPreviewMesh mesh = fixedMesh();
        ChainPreviewDrawPlan plan = derive(mesh, 0, mesh.getIndexCount(), null, null, 1L, 1L);
        Assert.assertFalse(plan.isAnimationEnabled());
        Assert.assertEquals(0, plan.getWaveVisible());
        Assert.assertEquals(plan.getIndexCount(), plan.getVisibleIndexCount());
        Assert.assertNull(plan.getWaveEnds());
    }

    @Test
    public void waveEndsAreNormalizedStrictlyIncreasingEndingAtIndexRangeEnd() {
        ChainPreviewMesh mesh = fixedMesh();
        ChainPreviewDrawPlan plan = derive(
            mesh, 0, mesh.getIndexCount(), new int[] {352, 300, 100, 0, -5, 5000}, null, 0L, 0L);
        int[] waves = plan.getWaveEnds();
        Assert.assertNotNull(waves);
        Assert.assertArrayEquals(new int[] {100, 300, 352}, waves);
        for (int index = 1; index < waves.length; index++) {
            Assert.assertTrue("必须严格递增", waves[index] > waves[index - 1]);
        }
        Assert.assertEquals(plan.getIndexOffset() + plan.getIndexCount(), waves[waves.length - 1]);

        ChainPreviewDrawPlan offsetPlan = derive(mesh, 100, 252, new int[] {352, 120, 200}, null, 0L, 0L);
        int[] offsetWaves = offsetPlan.getWaveEnds();
        Assert.assertArrayEquals(new int[] {120, 200, 352}, offsetWaves);
        Assert.assertEquals(
            offsetPlan.getIndexOffset() + offsetPlan.getIndexCount(),
            offsetWaves[offsetWaves.length - 1]);

        ChainPreviewDrawPlan degenerate = derive(mesh, 100, 252, new int[] {50, 90}, null, 0L, 0L);
        Assert.assertNull("全部波尾落在索引区间外时必须退化为关闭", degenerate.getWaveEnds());
    }

    @Test
    public void animationProgressSelectsVisibleWaves() {
        ChainPreviewMesh mesh = fixedMesh();
        int count = mesh.getIndexCount();
        int[] raw = {40, 120, 200, 280, 352};
        ChainPreviewDrawPlan atZero = derive(
            mesh, 0, count, raw, visuals(0.0F), 0L, 0L);
        Assert.assertEquals(0, atZero.getWaveVisible());
        Assert.assertEquals(0, atZero.getVisibleIndexCount());
        Assert.assertTrue(atZero.isAnimationEnabled());

        ChainPreviewDrawPlan atHalf = derive(mesh, 0, count, raw, visuals(0.5F), 0L, 0L);
        Assert.assertEquals(2, atHalf.getWaveVisible());
        Assert.assertEquals(120, atHalf.getVisibleIndexCount());

        ChainPreviewDrawPlan complete = derive(mesh, 0, count, raw, visuals(1.0F), 0L, 0L);
        Assert.assertEquals("animationU=1 时可见波数 == waveEnds.length", 5, complete.getWaveVisible());
        Assert.assertEquals(count, complete.getVisibleIndexCount());
    }

    @Test
    public void waveTableIsDefensiveCopy() {
        ChainPreviewMesh mesh = fixedMesh();
        ChainPreviewDrawPlan plan = derive(mesh, 0, mesh.getIndexCount(), new int[] {100, 200, 352}, null, 0L, 0L);
        int[] first = plan.getWaveEnds();
        int[] second = plan.getWaveEnds();
        Assert.assertNotSame(first, second);
        Arrays.fill(first, -1);
        Assert.assertArrayEquals(new int[] {100, 200, 352}, plan.getWaveEnds());
    }

    @Test
    public void sanitizedVisualsClampNaNNullAndOutOfRange() {
        ChainPreviewMesh mesh = fixedMesh();
        Visuals nan = new Visuals(
            Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, null, 1.0F);
        ChainPreviewDrawPlan nanPlan = derive(mesh, 0, mesh.getIndexCount(), null, nan, 0L, 0L);
        // NaN / null 必须收敛到与配置默认逐值同源的 BASELINE（render-core D10 加固后的口径）。
        Assert.assertEquals(
            "NaN 视觉参数必须收敛为 BASELINE",
            Visuals.BASELINE,
            nanPlan.getVisuals());
        Assert.assertEquals(ChainPreviewDrawPlan.DEFAULT_BAR_THICKNESS, nanPlan.getBarThickness(), 0.0F);
        Assert.assertEquals(0.0F, nanPlan.getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals(ChainPreviewDrawPlan.ANIMATION_COMPLETE, nanPlan.getAnimationU(), 0.0F);
        Assert.assertEquals(2.0F, nanPlan.getFadeStartRadius(), 0.0F);
        Assert.assertEquals(6.0F, nanPlan.getFadeEndRadius(), 0.0F);
        Assert.assertEquals(0.78F, nanPlan.getAlphaStart(), 0.0F);
        Assert.assertEquals(0.15F, nanPlan.getAlphaEnd(), 0.0F);
        Assert.assertEquals(DepthChannel.XRAY, nanPlan.getDepthChannel());

        Visuals ranged = new Visuals(1.5F, -3.0F, 2.0F, 5.0F, 1.0F, 2.0F, -1.0F, DepthChannel.XRAY, 1.0F);
        ChainPreviewDrawPlan rangedPlan = derive(mesh, 0, mesh.getIndexCount(), null, ranged, 0L, 0L);
        Assert.assertEquals(0.99F, rangedPlan.getBarThickness(), 0.0F);
        Assert.assertEquals(0.0F, rangedPlan.getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals(1.0F, rangedPlan.getAnimationU(), 0.0F);
        Assert.assertEquals(5.0F, rangedPlan.getFadeStartRadius(), 0.0F);
        Assert.assertEquals(5.001F, rangedPlan.getFadeEndRadius(), 0.00001F);
        Assert.assertEquals(1.0F, rangedPlan.getAlphaStart(), 0.0F);
        Assert.assertEquals(0.0F, rangedPlan.getAlphaEnd(), 0.0F);

        ChainPreviewDrawPlan baselinePlan = derive(mesh, 0, mesh.getIndexCount(), null, null, 0L, 0L);
        Assert.assertEquals(0.045F, baselinePlan.getBarThickness(), 0.0000001F);
        Assert.assertEquals(2.0F, baselinePlan.getFadeStartRadius(), 0.0F);
        Assert.assertEquals(6.0F, baselinePlan.getFadeEndRadius(), 0.0F);
    }

    @Test
    public void truncatedFlagAndVertexCountComeFromMesh() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            VerifyShapes.scatteredX(4097, 3), BUILDER_VISUALS);
        Assert.assertTrue(mesh.isTruncated());
        ChainPreviewDrawPlan plan = derive(mesh, 0, mesh.getIndexCount(), null, null, 7L, 11L);
        Assert.assertTrue(plan.isTruncated());
        Assert.assertEquals(mesh.getVertexFloatCount() / 3, plan.getVertexCount());
        Assert.assertEquals(7L, plan.getRebuilds());
        Assert.assertEquals(11L, plan.getUploads());
    }

    @Test
    public void fadeAlphaMultiplierIsRecordedWithoutDoubleScaling() {
        Visuals base = visuals(1.0F);
        Assert.assertEquals(1.0F, base.getFadeAlpha(), 0.0F);
        Assert.assertSame("乘子未变化必须零分配返回自身", base, base.withFadeAlpha(1.0F));

        Visuals half = base.withFadeAlpha(0.5F);
        Assert.assertEquals(0.5F, half.getFadeAlpha(), 0.0F);
        Assert.assertEquals("端点不得预乘淡入淡出乘子（避免 k²）", 0.78F, half.getAlphaStart(), 0.0F);
        Assert.assertEquals(0.15F, half.getAlphaEnd(), 0.0F);
        Assert.assertNotEquals("乘子参与值相等语义", base, half);
        Assert.assertNotEquals(base.hashCode(), half.hashCode());

        Assert.assertEquals("NaN → 1", 1.0F, base.withFadeAlpha(Float.NaN).getFadeAlpha(), 0.0F);
        Assert.assertEquals("负值 → 0", 0.0F, base.withFadeAlpha(-1.0F).getFadeAlpha(), 0.0F);
        Assert.assertEquals("超过 1 → 1", 1.0F, base.withFadeAlpha(2.0F).getFadeAlpha(), 0.0F);
        Assert.assertEquals(
            "自身已是 1 时 NaN 也必须零分配返回自身",
            base,
            base.withFadeAlpha(Float.NaN));
    }

    @Test
    public void fadeAlphaScalingEquivalenceHoldsAcrossDistances() {
        // 两条后端实现（端点缩放 vs 逐顶点乘子）等价的数学依据。
        float[] multipliers = {0.0F, 0.25F, 0.5F, 0.78F, 1.0F};
        float[] distances = {0.0F, 2.0F, 3.0F, 4.0F, 5.0F, 6.0F, 10.0F};
        for (float multiplier : multipliers) {
            for (float distance : distances) {
                float scaledEndpoints = Visuals.alphaFor(
                    distance, 2.0F, 6.0F, multiplier * 0.78F, multiplier * 0.15F);
                float multiplied = multiplier
                    * Visuals.alphaFor(distance, 2.0F, 6.0F, 0.78F, 0.15F);
                Assert.assertEquals(
                    "k=" + multiplier + " d=" + distance,
                    multiplied,
                    scaledEndpoints,
                    0.000001F);
            }
        }
    }

    @Test
    public void sanitizedClampsFadeAlpha() {
        Assert.assertEquals(
            1.0F,
            new Visuals(0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F, DepthChannel.XRAY, 2.5F)
                .sanitized().getFadeAlpha(),
            0.0F);
        Assert.assertEquals(
            1.0F,
            new Visuals(0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F, DepthChannel.XRAY, Float.NaN)
                .sanitized().getFadeAlpha(),
            0.0F);
        Assert.assertEquals(
            0.0F,
            new Visuals(0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F, DepthChannel.XRAY, -0.5F)
                .sanitized().getFadeAlpha(),
            0.0F);
    }

    @Test
    public void alphaForMatchesIndependentCurveAndAnchor() {
        float[] distances = {0.0F, 1.9F, 2.0F, 2.5F, 3.0F, 4.0F, 5.0F, 5.9F, 6.0F, 10.0F};
        for (float distance : distances) {
            float expected = independentAlpha(distance, 2.0F, 6.0F, 0.78F, 0.15F);
            float actual = Visuals.alphaFor(distance, 2.0F, 6.0F, 0.78F, 0.15F);
            Assert.assertEquals("d=" + distance, expected, actual, 0.000001F);
        }
        Assert.assertEquals(0.78F, Visuals.alphaFor(0.0F, 2.0F, 6.0F, 0.78F, 0.15F), 0.0F);
        Assert.assertEquals(0.15F, Visuals.alphaFor(100.0F, 2.0F, 6.0F, 0.78F, 0.15F), 0.0F);
        Assert.assertEquals(
            "d=4 锚点（独立 Python 模型 float32 收窄值）",
            ALPHA_AT_DISTANCE_4,
            Visuals.alphaFor(4.0F, 2.0F, 6.0F, 0.78F, 0.15F),
            0.000001F);
    }

    /** 统一走「origin 取 mesh origin」的生产调用形态。 */
    private static ChainPreviewDrawPlan derive(
            ChainPreviewMesh mesh,
            int indexOffset,
            int indexCount,
            int[] waveEnds,
            Visuals visuals,
            long rebuilds,
            long uploads) {
        return ChainPreviewDrawPlan.derive(
            mesh,
            indexOffset,
            indexCount,
            waveEnds,
            visuals,
            MASK,
            mesh == null ? 0 : mesh.getOriginX(),
            mesh == null ? 0 : mesh.getOriginY(),
            mesh == null ? 0 : mesh.getOriginZ(),
            rebuilds,
            uploads);
    }

    private static Visuals visuals(float animationU) {
        return new Visuals(0.045F, 0.0F, animationU, 2.0F, 6.0F, 0.78F, 0.15F, DepthChannel.XRAY, 1.0F);
    }

    private static ChainPreviewMesh fixedMesh() {
        return new ChainPreviewMeshBuilder().build(VerifyShapes.line(2), BUILDER_VISUALS);
    }

    /** 独立复算：与实现同形的距离二次淡出（不读取被测实现）。 */
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
