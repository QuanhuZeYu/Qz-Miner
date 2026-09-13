package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

public class ChainPreviewDrawPlanLodTest {

    private static final int MASK = ChainPreviewDrawPlan.SEMANTIC_MASK_ALL;

    @Test
    public void offModeIsDefaultAndHasZeroEffectiveThreshold() {
        ChainPreviewDrawPlan.Visuals.Lod off = ChainPreviewDrawPlan.Visuals.Lod.fromConfig("off", 0.05F);

        Assert.assertEquals("off", off.getModeId());
        Assert.assertEquals("off 档生效阈值必须为 0（未启用）", 0.0F, off.getMinAlpha(), 0.0F);
        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF, off);
        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF, ChainPreviewDrawPlan.Visuals.BASELINE.getLod());
    }

    @Test
    public void autoModeCarriesClampedThreshold() {
        ChainPreviewDrawPlan.Visuals.Lod auto = ChainPreviewDrawPlan.Visuals.Lod.fromConfig("auto", 0.05F);

        Assert.assertEquals("auto", auto.getModeId());
        Assert.assertEquals(0.05F, auto.getMinAlpha(), 1.0E-6F);
        Assert.assertEquals(1.0F, ChainPreviewDrawPlan.Visuals.Lod.fromConfig("auto", 2.0F).getMinAlpha(), 1.0E-6F);
        Assert.assertEquals(0.0F, ChainPreviewDrawPlan.Visuals.Lod.fromConfig("auto", -1.0F).getMinAlpha(), 1.0E-6F);
        Assert.assertEquals(0.0F, ChainPreviewDrawPlan.Visuals.Lod.fromConfig("auto", Float.NaN).getMinAlpha(), 1.0E-6F);
    }

    @Test
    public void nullOrUnknownModeFallsBackToOff() {
        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF,
            ChainPreviewDrawPlan.Visuals.Lod.fromConfig(null, 0.5F));
        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF,
            ChainPreviewDrawPlan.Visuals.Lod.fromConfig("bogus", 0.5F));
        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF,
            ChainPreviewDrawPlan.Visuals.Lod.fromConfig("", 0.5F));
        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF,
            ChainPreviewDrawPlan.Visuals.Lod.fromConfig("AUTO", 0.5F));
    }

    @Test
    public void planExposesEffectiveLodAndOffEqualsToday() {
        ChainPreviewDrawPlan offPlan = ChainPreviewDrawPlan.derive(
            null, 0, 0, null, ChainPreviewDrawPlan.Visuals.BASELINE, MASK, 0, 0, 0, 0L, 0L);

        Assert.assertEquals("off", offPlan.getLodId());
        Assert.assertEquals(0.0F, offPlan.getLodMinAlpha(), 0.0F);

        ChainPreviewDrawPlan autoPlan = ChainPreviewDrawPlan.derive(
            null, 0, 0, null, autoVisuals(), MASK, 0, 0, 0, 0L, 0L);

        Assert.assertEquals("auto", autoPlan.getLodId());
        Assert.assertEquals(0.05F, autoPlan.getLodMinAlpha(), 1.0E-6F);
    }

    @Test
    public void lodSurvivesFrameProgressRewrites() {
        ChainPreviewDrawPlan.Visuals rewritten = autoVisuals()
            .withAnimationU(0.5F)
            .withFadeAlpha(0.5F);

        Assert.assertEquals(0.5F, rewritten.getAnimationU(), 1.0E-6F);
        Assert.assertEquals(0.5F, rewritten.getFadeAlpha(), 1.0E-6F);
        Assert.assertEquals("auto", rewritten.getLod().getModeId());
        Assert.assertEquals(0.05F, rewritten.getLod().getMinAlpha(), 1.0E-6F);
    }

    @Test
    public void nullLodConvergesToOff() {
        ChainPreviewDrawPlan.Visuals raw = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, null);

        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF, raw.getLod());
        Assert.assertSame(ChainPreviewDrawPlan.Visuals.Lod.OFF, raw.sanitized().getLod());
    }

    @Test
    public void planCarriesMeshCulledTargetCountAndOffStaysZero() {
        ChainPreviewMesh offMesh = new ChainPreviewMesh(new float[6], new float[8], new int[4], 1, null, 0);
        ChainPreviewDrawPlan offPlan = ChainPreviewDrawPlan.derive(
            offMesh, 0, 4, null, ChainPreviewDrawPlan.Visuals.BASELINE, MASK, 0, 0, 0, 0L, 0L);

        Assert.assertEquals("lod=off 必须零剔除", 0, offPlan.getCulledTargetCount());
        Assert.assertEquals("off", offPlan.getLodId());

        ChainPreviewMesh culledMesh = new ChainPreviewMesh(new float[6], new float[8], new int[4], 1, null, 7);
        ChainPreviewDrawPlan culledPlan = ChainPreviewDrawPlan.derive(
            culledMesh, 0, 4, null, autoVisuals(), MASK, 0, 0, 0, 0L, 0L);

        Assert.assertEquals(7, culledPlan.getCulledTargetCount());
        Assert.assertEquals("auto", culledPlan.getLodId());
        Assert.assertEquals(0.05F, culledPlan.getLodMinAlpha(), 1.0E-6F);
    }

    @Test
    public void culledTargetsAccumulateInCountersAndAppearInPlanSnapshot() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        counters.record(ChainPreviewRefreshDecision.Upload.TOPOLOGY, true);
        counters.recordCulled(7);
        counters.recordCulled(0);

        Assert.assertEquals(7L, counters.getCulledTargets());
        Assert.assertEquals(1L, counters.getCullEvents());

        ChainPreviewMesh culledMesh = new ChainPreviewMesh(new float[6], new float[8], new int[4], 1, null, 7);
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            culledMesh, 0, 4, null, autoVisuals(), MASK, 0, 0, 0,
            counters.getRebuilds(), counters.getUploads());

        Assert.assertEquals(7, plan.getCulledTargetCount());
        Assert.assertTrue(counters.describe().contains("preview.culledTargets=7"));
    }

    private static ChainPreviewDrawPlan.Visuals autoVisuals() {
        return new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.BUILTIN,
            ChainPreviewDrawPlan.Visuals.Lod.fromConfig("auto", 0.05F));
    }
}
