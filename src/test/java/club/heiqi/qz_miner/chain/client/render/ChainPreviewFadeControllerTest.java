package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewFadeControllerTest {

    private static final int DURATION_MS = 1000;
    private static final long MS = 1_000_000L;

    @Test
    public void fadeGateOnlyEnablesFlowAndWaveWithPositiveDuration() {
        Assert.assertTrue(ChainPreviewFadeController.isFadeEnabled("wave", 120));
        Assert.assertTrue(ChainPreviewFadeController.isFadeEnabled("flow", 120));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("off", 120));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled(null, 120));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("bogus", 120));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("wave", 0));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("wave", -5));
    }

    @Test
    public void disabledFadeKeepsHistoricalBehaviour() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();

        Assert.assertEquals(1.0F, controller.advance(true, 1, false, 0, 0L), 0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.ACTIVE, controller.getPhase());
        Assert.assertEquals("off 档未激活且 IDLE：alpha 防御性归 0", 0.0F,
            controller.advance(false, 1, false, 0, 10 * MS), 0.0F);
        Assert.assertTrue("off 档结束必须立即 IDLE（无 retiring）", controller.isIdle());
    }

    @Test
    public void fadeInProgressesToComplete() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();

        Assert.assertEquals(0.0F, controller.advance(true, 7, true, DURATION_MS, 0L), 1.0E-6F);
        Assert.assertEquals(0.25F, controller.advance(true, 7, true, DURATION_MS, 250 * MS), 1.0E-6F);
        Assert.assertEquals(0.5F, controller.advance(true, 7, true, DURATION_MS, 500 * MS), 1.0E-6F);
        Assert.assertEquals(1.0F, controller.advance(true, 7, true, DURATION_MS, 1000 * MS), 1.0E-6F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.ACTIVE, controller.getPhase());
    }

    @Test
    public void retiringStartsFromCurrentAlphaAndFadesToIdle() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        controller.advance(true, 3, true, DURATION_MS, 0L);
        controller.advance(true, 3, true, DURATION_MS, 1000 * MS);

        Assert.assertEquals(1.0F, controller.advance(false, 3, true, DURATION_MS, 2000 * MS), 1.0E-6F);
        Assert.assertTrue(controller.isRetiring());
        Assert.assertEquals(0.5F, controller.advance(false, 3, true, DURATION_MS, 2500 * MS), 1.0E-6F);
        Assert.assertEquals(0.0F, controller.advance(false, 3, true, DURATION_MS, 3000 * MS), 1.0E-6F);
        Assert.assertTrue(controller.isIdle());
        Assert.assertEquals(0.0F, controller.getAlpha(), 1.0E-6F);
    }

    @Test
    public void retiringFromPartialFadeInUsesCurrentAlphaAsStart() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();

        Assert.assertEquals(0.0F, controller.advance(true, 3, true, DURATION_MS, 0L), 1.0E-6F);
        Assert.assertEquals(0.4F, controller.advance(true, 3, true, DURATION_MS, 400 * MS), 1.0E-6F);
        Assert.assertEquals(0.4F, controller.advance(false, 3, true, DURATION_MS, 500 * MS), 1.0E-6F);
        Assert.assertEquals(0.2F, controller.advance(false, 3, true, DURATION_MS, 1000 * MS), 1.0E-6F);
    }

    @Test
    public void newGenerationPreemptsRetiringAndRestartsFadeIn() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        controller.advance(true, 1, true, DURATION_MS, 0L);
        controller.advance(true, 1, true, DURATION_MS, 1000 * MS);
        controller.advance(false, 1, true, DURATION_MS, 2000 * MS);
        controller.advance(false, 1, true, DURATION_MS, 2400 * MS);
        Assert.assertTrue(controller.isRetiring());

        Assert.assertEquals(0.0F, controller.advance(true, 2, true, DURATION_MS, 2500 * MS), 1.0E-6F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.FADING_IN, controller.getPhase());
        Assert.assertEquals(2, controller.getGeneration());
    }

    @Test
    public void largeFrameGapOnlyJumpsForward() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();

        controller.advance(true, 1, true, DURATION_MS, 0L);
        Assert.assertEquals(1.0F, controller.advance(true, 1, true, DURATION_MS, 60_000 * MS), 1.0E-6F);

        controller.advance(false, 1, true, DURATION_MS, 61_000 * MS);
        Assert.assertEquals(0.0F, controller.advance(false, 1, true, DURATION_MS, 62_000 * MS), 1.0E-6F);
        Assert.assertTrue(controller.isIdle());
    }

    @Test
    public void durationIsCappedAndBackwardsClockNeverGoesNegative() {
        ChainPreviewFadeController capped = new ChainPreviewFadeController();
        capped.advance(true, 1, true, 999_999, 0L);
        Assert.assertEquals(1.0F,
            capped.advance(true, 1, true, 999_999, ChainPreviewFadeController.MAX_FADE_MS * MS), 1.0E-6F);

        ChainPreviewFadeController backwards = new ChainPreviewFadeController();
        backwards.advance(true, 1, true, DURATION_MS, 5000 * MS);
        Assert.assertEquals(0.0F, backwards.advance(true, 1, true, DURATION_MS, 4000 * MS), 1.0E-6F);
    }

    @Test
    public void resetClearsPhaseAndGeneration() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        controller.advance(true, 9, true, DURATION_MS, 0L);
        controller.advance(false, 9, true, DURATION_MS, 100 * MS);
        Assert.assertTrue(controller.isRetiring());

        controller.reset();

        Assert.assertTrue(controller.isIdle());
        Assert.assertEquals(Integer.MIN_VALUE, controller.getGeneration());
        Assert.assertEquals(0.0F, controller.getAlpha(), 1.0E-6F);
    }

    @Test
    public void idlePhaseAlwaysReportsZeroAlpha() {
        ChainPreviewFadeController fresh = new ChainPreviewFadeController();
        Assert.assertTrue(fresh.isIdle());
        Assert.assertEquals(0.0F, fresh.getAlpha(), 0.0F);

        ChainPreviewFadeController retired = new ChainPreviewFadeController();
        retired.advance(true, 1, true, DURATION_MS, 0L);
        retired.advance(true, 1, true, DURATION_MS, DURATION_MS * MS);
        retired.advance(false, 1, true, DURATION_MS, 2 * DURATION_MS * MS);
        retired.advance(false, 1, true, DURATION_MS, 3 * DURATION_MS * MS);

        Assert.assertTrue(retired.isIdle());
        Assert.assertEquals(0.0F, retired.getAlpha(), 0.0F);
        Assert.assertEquals(0.0F, retired.advance(false, 1, true, DURATION_MS, 9 * DURATION_MS * MS), 0.0F);
    }
}
