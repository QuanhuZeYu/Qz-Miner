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
    public void resetClearsBothCounters() {
        ChainPreviewScaleCounters counters = new ChainPreviewScaleCounters();
        counters.recordTopologyUpload();
        counters.recordColorUpload();

        counters.reset();

        Assert.assertEquals(0L, counters.getRebuilds());
        Assert.assertEquals(0L, counters.getUploads());
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
