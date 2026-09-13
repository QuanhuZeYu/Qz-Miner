package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewRefreshDecisionTest {

    @Test
    public void topologyChangeRetransfersForBothBackendKinds() {
        Assert.assertEquals(ChainPreviewRefreshDecision.Upload.TOPOLOGY,
            ChainPreviewRefreshDecision.begin(true, true));
        Assert.assertEquals(ChainPreviewRefreshDecision.Upload.TOPOLOGY,
            ChainPreviewRefreshDecision.begin(true, false));
    }

    @Test
    public void shaderSameGenerationRefreshIsZeroUpload() {
        Assert.assertEquals(ChainPreviewRefreshDecision.Upload.NONE,
            ChainPreviewRefreshDecision.begin(false, false));
        Assert.assertEquals(ChainPreviewRefreshDecision.Upload.NONE,
            ChainPreviewRefreshDecision.fallback(ChainPreviewRefreshDecision.Upload.NONE));
    }

    @Test
    public void legacySameGenerationRefreshUsesColorStream() {
        Assert.assertEquals(ChainPreviewRefreshDecision.Upload.COLORS,
            ChainPreviewRefreshDecision.begin(false, true));
        Assert.assertEquals(ChainPreviewRefreshDecision.Upload.TOPOLOGY,
            ChainPreviewRefreshDecision.fallback(ChainPreviewRefreshDecision.Upload.COLORS));
        Assert.assertEquals(ChainPreviewRefreshDecision.Upload.TOPOLOGY,
            ChainPreviewRefreshDecision.fallback(ChainPreviewRefreshDecision.Upload.TOPOLOGY));
    }

    @Test
    public void shaderSameGenerationRefreshKeepsCountersAndPlanSnapshotUnchanged() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        counters.recordTopologyUpload();
        long rebuildsBefore = counters.getRebuilds();
        long uploadsBefore = counters.getUploads();

        ChainPreviewRefreshDecision.Upload upload =
            ChainPreviewRefreshDecision.begin(false, false);
        counters.record(upload, true);

        Assert.assertEquals(uploadsBefore, counters.getUploads());
        Assert.assertEquals(rebuildsBefore, counters.getRebuilds());

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
        Assert.assertEquals(rebuildsBefore, plan.getRebuilds());
        Assert.assertEquals(uploadsBefore, plan.getUploads());
    }

    @Test
    public void legacyColorRefreshOnlyIncrementsUploads() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();

        counters.record(ChainPreviewRefreshDecision.begin(false, true), true);

        Assert.assertEquals(0L, counters.getRebuilds());
        Assert.assertEquals(1L, counters.getUploads());
    }

    @Test
    public void legacyColorRejectionStillRetransfersTopologyOnCounters() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        ChainPreviewRefreshDecision.Upload upload =
            ChainPreviewRefreshDecision.begin(false, true);
        upload = ChainPreviewRefreshDecision.fallback(upload);

        counters.record(upload, true);

        Assert.assertEquals(1L, counters.getRebuilds());
        Assert.assertEquals(1L, counters.getUploads());
    }

    @Test
    public void emptyMeshClearIsNotCountedAsRebuild() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();

        counters.record(ChainPreviewRefreshDecision.begin(true, false), false);

        Assert.assertEquals(0L, counters.getRebuilds());
        Assert.assertEquals(0L, counters.getUploads());
    }
}
