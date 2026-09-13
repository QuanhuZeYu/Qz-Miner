package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewScaleCountersTest {

    @Test
    public void countsTopologyAndColorUploadsSeparately() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();

        Assert.assertEquals(0L, counters.getRebuilds());
        Assert.assertEquals(0L, counters.getUploads());

        counters.recordColorUpload();
        Assert.assertEquals(0L, counters.getRebuilds());
        Assert.assertEquals(1L, counters.getUploads());

        counters.recordTopologyUpload();
        Assert.assertEquals(1L, counters.getRebuilds());
        Assert.assertEquals(2L, counters.getUploads());
    }

    @Test
    public void bindingCaptureCountsThreeIntegerReads() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();

        Assert.assertEquals(0L, counters.getFrameCaptures());
        Assert.assertEquals(0L, counters.getGlIntegerReads());

        counters.recordBindingCapture();
        counters.recordBindingCapture();

        Assert.assertEquals(2L, counters.getFrameCaptures());
        Assert.assertEquals(2L * ChainPreviewGlBindings.CAPTURED_QUERY_COUNT, counters.getGlIntegerReads());
        Assert.assertEquals(6L, counters.getGlIntegerReads());
        Assert.assertTrue(counters.describe().contains("preview.glIntegerReads=6"));
    }

    @Test
    public void resetClearsAllCounters() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        counters.recordTopologyUpload();
        counters.recordColorUpload();
        counters.recordBindingCapture();

        counters.reset();

        Assert.assertEquals(0L, counters.getRebuilds());
        Assert.assertEquals(0L, counters.getUploads());
        Assert.assertEquals(0L, counters.getFrameCaptures());
        Assert.assertEquals(0L, counters.getGlIntegerReads());
    }

    @Test
    public void culledQuadCountsAccumulateOnlyForPositiveValues() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();

        counters.recordCulled(0);
        counters.recordCulled(-4);
        Assert.assertEquals(0L, counters.getCulledTargets());
        Assert.assertEquals(0L, counters.getCullEvents());

        counters.recordCulled(5);
        counters.recordCulled(3);
        Assert.assertEquals(8L, counters.getCulledTargets());
        Assert.assertEquals(2L, counters.getCullEvents());
        Assert.assertTrue(counters.describe().contains("preview.culledTargets=8"));
        Assert.assertTrue(counters.describe().contains("preview.cullEvents=2"));

        counters.reset();
        Assert.assertEquals(0L, counters.getCulledTargets());
        Assert.assertEquals(0L, counters.getCullEvents());
    }

    @Test
    public void counterSnapshotIsCarriedByDrawPlan() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        counters.recordTopologyUpload();
        counters.recordColorUpload();
        counters.recordColorUpload();

        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            null,
            0,
            0,
            null,
            ChainPreviewDrawPlan.Visuals.BASELINE,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
            0,
            0,
            0,
            counters.getRebuilds(),
            counters.getUploads());

        Assert.assertEquals(1L, plan.getRebuilds());
        Assert.assertEquals(3L, plan.getUploads());
    }
}
