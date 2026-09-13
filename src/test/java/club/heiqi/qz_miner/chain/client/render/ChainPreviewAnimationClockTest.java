package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

public class ChainPreviewAnimationClockTest {

    @Test
    public void offOrUnknownModeIsAlwaysComplete() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(1.0F, clock.advance(3, "off", 120, 1_000_000L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(3, null, 120, 2_000_000L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(3, "bogus", 120, 3_000_000L), 1.0E-6F);
        Assert.assertFalse(clock.isAnimating());
    }

    @Test
    public void waveModeAdvancesLinearlyThroughStartHalfAndComplete() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(9, "wave", 1000, 0L), 1.0E-6F);
        Assert.assertEquals(0.25F, clock.advance(9, "wave", 1000, 250_000_000L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(9, "wave", 1000, 500_000_000L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(9, "wave", 1000, 1_000_000_000L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(9, "wave", 1000, 5_000_000_000L), 1.0E-6F);
    }

    @Test
    public void flowModeAdvancesLikeWave() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(1, "flow", 200, 100L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(1, "flow", 200, 100_000_000L + 100L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(1, "flow", 200, 200_000_000L + 100L), 1.0E-6F);
    }

    @Test
    public void zeroOrNegativeDurationConvergesImmediately() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(1.0F, clock.advance(1, "wave", 0, 10L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(1, "wave", -5, 20L), 1.0E-6F);
        Assert.assertFalse(clock.isAnimating());
    }

    @Test
    public void generationChangeRestartsClock() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 0L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(1, "wave", 1000, 500_000_000L), 1.0E-6F);
        Assert.assertEquals(0.0F, clock.advance(2, "wave", 1000, 600_000_000L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(2, "wave", 1000, 1_100_000_000L), 1.0E-6F);
    }

    @Test
    public void modeSwitchOffAndBackRestartsClock() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 0L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(1, "off", 1000, 500_000_000L), 1.0E-6F);
        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 600_000_000L), 1.0E-6F);
    }

    @Test
    public void hugeFrameGapJumpsForwardButNeverOvershoots() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 0L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(1, "wave", 1000, 10_000_000_000L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(1, "wave", 1000, 20_000_000_000L), 1.0E-6F);
    }

    @Test
    public void backwardsOrSameTimestampNeverProducesNegativeProgress() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 1_000_000_000L), 1.0E-6F);
        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 999_000_000L), 1.0E-6F);
        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 1_000_000_000L), 1.0E-6F);
    }

    @Test
    public void extremeInputsAreNarrowedWithoutThrowing() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        float start = clock.advance(Integer.MIN_VALUE, "wave", Integer.MAX_VALUE, Long.MIN_VALUE);
        Assert.assertEquals(0.0F, start, 1.0E-6F);

        float overflowSample = clock.advance(Integer.MIN_VALUE, "wave", Integer.MAX_VALUE, Long.MAX_VALUE);
        Assert.assertTrue(overflowSample >= 0.0F && overflowSample <= 1.0F);
    }

    @Test
    public void resetClearsGenerationAndRestartsProgress() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 0L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(1, "wave", 1000, 500_000_000L), 1.0E-6F);

        clock.reset();
        Assert.assertEquals(Integer.MIN_VALUE, clock.getGeneration());
        Assert.assertEquals(1.0F, clock.getAnimationU(), 1.0E-6F);
        Assert.assertEquals(0.0F, clock.advance(1, "wave", 1000, 5_000_000_000L), 1.0E-6F);
    }

    @Test
    public void sameGenerationDurationChangeRestartsTimelineImmediately() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(4, "wave", 1000, 0L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(4, "wave", 1000, 500_000_000L), 1.0E-6F);

        // 同代把 duration 从 1000ms 改成 200ms：本帧即按新一轮从 0 重新计时
        Assert.assertEquals(0.0F, clock.advance(4, "wave", 200, 600_000_000L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(4, "wave", 200, 700_000_000L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(4, "wave", 200, 800_000_000L), 1.0E-6F);
    }

    @Test
    public void unchangedDurationDoesNotRestartTimeline() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(4, "wave", 400, 0L), 1.0E-6F);
        Assert.assertEquals(0.25F, clock.advance(4, "wave", 400, 100_000_000L), 1.0E-6F);
        Assert.assertEquals(0.75F, clock.advance(4, "wave", 400, 300_000_000L), 1.0E-6F);
        Assert.assertEquals(1.0F, clock.advance(4, "wave", 400, 400_000_000L), 1.0E-6F);
    }

    @Test
    public void modeChangeBetweenFlowAndWaveRestartsTimeline() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();

        Assert.assertEquals(0.0F, clock.advance(5, "wave", 1000, 0L), 1.0E-6F);
        Assert.assertEquals(0.5F, clock.advance(5, "wave", 1000, 500_000_000L), 1.0E-6F);

        // wave -> flow 同代切换：同样按新一轮处理
        Assert.assertEquals(0.0F, clock.advance(5, "flow", 1000, 600_000_000L), 1.0E-6F);
        Assert.assertEquals(0.3F, clock.advance(5, "flow", 1000, 900_000_000L), 1.0E-6F);
    }

    @Test
    public void repeatedSameTimestampIsDeterministic() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        clock.advance(4, "wave", 400, 0L);

        float first = clock.advance(4, "wave", 400, 100_000_000L);
        for (int i = 0; i < 16; i++) {
            Assert.assertEquals(first, clock.advance(4, "wave", 400, 100_000_000L), 1.0E-6F);
        }
    }
}
