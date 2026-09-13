package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

public class ChainPreviewDrawPlanTest {

    private static final int MASK = ChainPreviewDrawPlan.SEMANTIC_MASK_ALL;

    @Test
    public void derivePacksGeometryAndBaselineVisuals() {
        ChainPreviewMesh mesh = mesh(36, 8);
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh, 0, 36, null, null, MASK, 42, -7, 9, 3L, 5L);

        Assert.assertEquals(0, plan.getIndexOffset());
        Assert.assertEquals(36, plan.getIndexCount());
        Assert.assertEquals(9, plan.getQuadCount());
        Assert.assertEquals(8, plan.getVertexCount());
        Assert.assertEquals(36, plan.getVisibleIndexCount());
        Assert.assertEquals(0, plan.getWaveVisible());
        Assert.assertFalse(plan.isAnimationEnabled());
        Assert.assertNull(plan.getWaveEnds());
        Assert.assertEquals(42, plan.getOriginX());
        Assert.assertEquals(-7, plan.getOriginY());
        Assert.assertEquals(9, plan.getOriginZ());
        Assert.assertFalse(plan.isTruncated());
        Assert.assertEquals(3L, plan.getRebuilds());
        Assert.assertEquals(5L, plan.getUploads());

        Assert.assertEquals(ChainPreviewDrawPlan.DEFAULT_BAR_THICKNESS, plan.getBarThickness(), 1.0E-6F);
        Assert.assertEquals(1.0F, plan.getMinScreenWidthPx(), 1.0E-6F);
        Assert.assertEquals(ChainPreviewDrawPlan.ANIMATION_COMPLETE, plan.getAnimationU(), 1.0E-6F);
        Assert.assertEquals(2.0F, plan.getFadeStartRadius(), 1.0E-6F);
        Assert.assertEquals(6.0F, plan.getFadeEndRadius(), 1.0E-6F);
        Assert.assertEquals(0.78F, plan.getAlphaStart(), 1.0E-6F);
        Assert.assertEquals(0.15F, plan.getAlphaEnd(), 1.0E-6F);
        Assert.assertEquals(ChainPreviewDrawPlan.DepthChannel.XRAY, plan.getDepthChannel());
    }

    @Test
    public void deriveCarriesMeshOriginForTranslation() {
        ChainPreviewMesh mesh = mesh(36, 8);
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh, 0, 36, null, null, MASK,
            mesh.getOriginX(), mesh.getOriginY(), mesh.getOriginZ(), 0L, 0L);

        Assert.assertEquals(mesh.getOriginX(), plan.getOriginX());
        Assert.assertEquals(mesh.getOriginY(), plan.getOriginY());
        Assert.assertEquals(mesh.getOriginZ(), plan.getOriginZ());
    }

    @Test
    public void deriveClampsIndexRangeToMesh() {
        ChainPreviewMesh mesh = mesh(36, 8);

        ChainPreviewDrawPlan fromOffset = ChainPreviewDrawPlan.derive(
            mesh, 10, 999, null, null, MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(10, fromOffset.getIndexOffset());
        Assert.assertEquals(26, fromOffset.getIndexCount());
        Assert.assertEquals(6, fromOffset.getQuadCount());
        Assert.assertEquals(26, fromOffset.getVisibleIndexCount());

        ChainPreviewDrawPlan beyondEnd = ChainPreviewDrawPlan.derive(
            mesh, 100, 5, null, null, MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(36, beyondEnd.getIndexOffset());
        Assert.assertEquals(0, beyondEnd.getIndexCount());
        Assert.assertEquals(0, beyondEnd.getQuadCount());
        Assert.assertEquals(0, beyondEnd.getVisibleIndexCount());
    }

    @Test
    public void deriveNullMeshYieldsEmptyPlan() {
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            null, 0, 12, null, null, MASK, 0, 0, 0, 1L, 1L);

        Assert.assertEquals(0, plan.getIndexOffset());
        Assert.assertEquals(0, plan.getIndexCount());
        Assert.assertEquals(0, plan.getVertexCount());
        Assert.assertEquals(0, plan.getVisibleIndexCount());
        Assert.assertEquals(1L, plan.getRebuilds());
        Assert.assertEquals(1L, plan.getUploads());
    }

    @Test
    public void sanitizedNarrowsMalformedFields() {
        ChainPreviewDrawPlan raw = new ChainPreviewDrawPlan(
            -4,
            -9,
            new int[] { 50, 10, 10, -3, 999 },
            new ChainPreviewDrawPlan.Visuals(Float.NaN, -5.0F, Float.NaN, -1.0F, Float.NaN, 2.0F, -1.0F, null),
            MASK,
            5,
            6,
            7,
            -7,
            false,
            -1L,
            -2L);

        ChainPreviewDrawPlan plan = raw.sanitized();

        Assert.assertEquals(0, plan.getIndexOffset());
        Assert.assertEquals(0, plan.getIndexCount());
        Assert.assertEquals(0, plan.getQuadCount());
        Assert.assertEquals(0, plan.getVisibleIndexCount());
        Assert.assertNull(plan.getWaveEnds());
        Assert.assertEquals(0, plan.getVertexCount());
        Assert.assertEquals(0L, plan.getRebuilds());
        Assert.assertEquals(0L, plan.getUploads());
        Assert.assertEquals(5, plan.getOriginX());
        Assert.assertEquals(6, plan.getOriginY());
        Assert.assertEquals(7, plan.getOriginZ());
        Assert.assertEquals(ChainPreviewDrawPlan.DEFAULT_BAR_THICKNESS, plan.getBarThickness(), 1.0E-6F);
        Assert.assertEquals(0.0F, plan.getMinScreenWidthPx(), 1.0E-6F);
        Assert.assertEquals(ChainPreviewDrawPlan.ANIMATION_COMPLETE, plan.getAnimationU(), 1.0E-6F);
        Assert.assertEquals(0.0F, plan.getFadeStartRadius(), 1.0E-6F);
        Assert.assertEquals(ChainPreviewDrawPlan.MIN_FADE_SPAN, plan.getFadeEndRadius(), 1.0E-6F);
        Assert.assertEquals(1.0F, plan.getAlphaStart(), 1.0E-6F);
        Assert.assertEquals(0.0F, plan.getAlphaEnd(), 1.0E-6F);
        Assert.assertEquals(ChainPreviewDrawPlan.DepthChannel.XRAY, plan.getDepthChannel());
    }

    @Test
    public void sanitizedNormalizesWaveEndsToStrictIncreasingTail() {
        ChainPreviewDrawPlan raw = new ChainPreviewDrawPlan(
            0,
            100,
            new int[] { 100, 30, 30, -5, 130 },
            visuals(ChainPreviewDrawPlan.ANIMATION_COMPLETE),
            MASK,
            0,
            0,
            0,
            25,
            false,
            0L,
            0L);

        ChainPreviewDrawPlan plan = raw.sanitized();

        Assert.assertArrayEquals(new int[] { 30, 100 }, plan.getWaveEnds());
        Assert.assertTrue(plan.isAnimationEnabled());
        Assert.assertEquals(2, plan.getWaveVisible());
        Assert.assertEquals(100, plan.getVisibleIndexCount());
        Assert.assertEquals(25, plan.getQuadCount());
    }

    @Test
    public void waveGrowthSelectsVisiblePrefix() {
        ChainPreviewMesh mesh = mesh(100, 25);
        int[] waves = new int[] { 25, 50, 75, 100 };

        ChainPreviewDrawPlan hidden = ChainPreviewDrawPlan.derive(
            mesh, 0, 100, waves, visuals(0.0F), MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(0, hidden.getWaveVisible());
        Assert.assertEquals(0, hidden.getVisibleIndexCount());

        ChainPreviewDrawPlan first = ChainPreviewDrawPlan.derive(
            mesh, 0, 100, waves, visuals(0.3F), MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(1, first.getWaveVisible());
        Assert.assertEquals(25, first.getVisibleIndexCount());

        ChainPreviewDrawPlan half = ChainPreviewDrawPlan.derive(
            mesh, 0, 100, waves, visuals(0.5F), MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(2, half.getWaveVisible());
        Assert.assertEquals(50, half.getVisibleIndexCount());

        ChainPreviewDrawPlan full = ChainPreviewDrawPlan.derive(
            mesh, 0, 100, waves, visuals(1.0F), MASK, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(4, full.getWaveVisible());
        Assert.assertEquals(100, full.getVisibleIndexCount());
    }

    @Test
    public void waveEndsAreAppendedToCoverFullRange() {
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh(100, 25), 0, 100, new int[] { 40 }, visuals(1.0F), MASK, 0, 0, 0, 0L, 0L);

        Assert.assertArrayEquals(new int[] { 40, 100 }, plan.getWaveEnds());
        Assert.assertEquals(2, plan.getWaveVisible());
        Assert.assertEquals(100, plan.getVisibleIndexCount());
    }

    @Test
    public void sanitizedIsIdempotent() {
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh(36, 8), 0, 36, new int[] { 12, 36 }, visuals(0.5F), MASK, 0, 0, 0, 1L, 2L);

        Assert.assertSame(plan, plan.sanitized());
    }

    @Test
    public void waveEndsAccessorReturnsDefensiveCopy() {
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh(36, 8), 0, 36, new int[] { 12, 36 }, visuals(1.0F), MASK, 0, 0, 0, 0L, 0L);

        int[] ends = plan.getWaveEnds();
        Assert.assertArrayEquals(new int[] { 12, 36 }, ends);
        ends[0] = -1;
        Assert.assertArrayEquals(new int[] { 12, 36 }, plan.getWaveEnds());
    }

    @Test
    public void identicalInputsProduceEqualPlans() {
        ChainPreviewMesh mesh = mesh(36, 8);
        ChainPreviewDrawPlan first = ChainPreviewDrawPlan.derive(
            mesh, 0, 36, new int[] { 12, 36 }, visuals(0.5F), MASK, 1, 2, 3, 4L, 9L);
        ChainPreviewDrawPlan second = ChainPreviewDrawPlan.derive(
            mesh, 0, 36, new int[] { 12, 36 }, visuals(0.5F), MASK, 1, 2, 3, 4L, 9L);

        Assert.assertEquals(first, second);
        Assert.assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void alphaForMatchesCpuCurveAtSixSamples() {
        float fadeStart = 2.0F;
        float fadeEnd = 6.0F;
        float maxAlpha = 0.78F;
        float minAlpha = 0.15F;

        Assert.assertEquals(maxAlpha,
            ChainPreviewDrawPlan.Visuals.alphaFor(2.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0E-5F);
        Assert.assertEquals(minAlpha,
            ChainPreviewDrawPlan.Visuals.alphaFor(6.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0E-5F);
        Assert.assertEquals(maxAlpha,
            ChainPreviewDrawPlan.Visuals.alphaFor(1.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0E-5F);
        Assert.assertEquals(minAlpha,
            ChainPreviewDrawPlan.Visuals.alphaFor(7.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0E-5F);
        // 中点（归一化 0.5 → squared 0.25）与四分点（0.25 → 0.0625），Python 验算：0.6225 / 0.740625
        Assert.assertEquals(0.6225F,
            ChainPreviewDrawPlan.Visuals.alphaFor(4.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0E-5F);
        Assert.assertEquals(0.740625F,
            ChainPreviewDrawPlan.Visuals.alphaFor(3.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0E-5F);

        Assert.assertEquals(maxAlpha,
            ChainPreviewDrawPlan.Visuals.alphaFor(5.0D, 5.0F, 3.0F, maxAlpha, minAlpha), 1.0E-5F);
        Assert.assertEquals(minAlpha,
            ChainPreviewDrawPlan.Visuals.alphaFor(10.0D, 5.0F, 3.0F, maxAlpha, minAlpha), 1.0E-5F);
    }

    private static ChainPreviewDrawPlan.Visuals visuals(float animationU) {
        return new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, animationU, 2.0F, 6.0F, 0.78F, 0.15F, ChainPreviewDrawPlan.DepthChannel.XRAY);
    }

    private static ChainPreviewMesh mesh(int indexCount, int vertexCount) {
        return new ChainPreviewMesh(
            new float[vertexCount * 3],
            new float[vertexCount * 4],
            new int[indexCount],
            1);
    }
}
