package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewAnimationClock;

/**
 * T12 动画时间线独立契约探针（B3.1）。
 *
 * <p>时钟是纯标量状态机：只依赖传入的 nowNanos，可在 JVM 内确定性断言。
 * 独立口径来自 temp/chain-preview/verify/wave2_model.json：
 * off / 未知档 / duration&lt;=0 → 恒完成；flow / wave 按 duration 线性推进；
 * 代变化重新计时；掉帧只跳进（钳制到 1）；时钟回退不出负值；reset 清空且不跨代残留。</p>
 */
public class AnimationClockContractTest {

    private static final int DURATION_MS = 120;
    private static final long NANOS_PER_MS = 1_000_000L;
    private static final long BASE = 5_000_000_000L;

    @Test
    public void offAndUnknownModesAlwaysComplete() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        for (String mode : new String[] {"off", null, "", "unknown", "OFF", " flow "}) {
            Assert.assertEquals(
                "mode=" + mode,
                ChainPreviewAnimationClock.COMPLETE,
                clock.advance(7, mode, DURATION_MS, BASE),
                0.0F);
            Assert.assertFalse("mode=" + mode + " 不得处于动画态", clock.isAnimating());
        }
        Assert.assertEquals(7, clock.getGeneration());
        Assert.assertEquals(ChainPreviewAnimationClock.COMPLETE, clock.getAnimationU(), 0.0F);
    }

    @Test
    public void nonPositiveDurationCompletesImmediately() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        Assert.assertEquals(ChainPreviewAnimationClock.COMPLETE, clock.advance(1, "wave", 0, BASE), 0.0F);
        Assert.assertFalse(clock.isAnimating());
        Assert.assertEquals(ChainPreviewAnimationClock.COMPLETE, clock.advance(1, "flow", -5, BASE), 0.0F);
        Assert.assertFalse(clock.isAnimating());
    }

    @Test
    public void waveAndFlowAdvanceLinearlyFromGenerationStart() {
        for (String mode : new String[] {"wave", "flow"}) {
            ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
            Assert.assertEquals(
                "首帧必须从起点开始",
                ChainPreviewAnimationClock.START,
                clock.advance(3, mode, DURATION_MS, BASE),
                0.0F);
            Assert.assertTrue(clock.isAnimating());
            Assert.assertEquals(3, clock.getGeneration());

            Assert.assertEquals(
                "同帧重入保持在起点",
                ChainPreviewAnimationClock.START,
                clock.advance(3, mode, DURATION_MS, BASE),
                0.0F);
            Assert.assertEquals(
                "1/4 时长为 0.25",
                0.25F,
                clock.advance(3, mode, DURATION_MS, BASE + 30 * NANOS_PER_MS),
                0.0001F);
            Assert.assertEquals(
                "半时长为 0.5",
                0.5F,
                clock.advance(3, mode, DURATION_MS, BASE + 60 * NANOS_PER_MS),
                0.0001F);
            Assert.assertEquals(
                "3/4 时长为 0.75",
                0.75F,
                clock.advance(3, mode, DURATION_MS, BASE + 90 * NANOS_PER_MS),
                0.0001F);
            Assert.assertEquals(
                "恰好满时长即完成",
                ChainPreviewAnimationClock.COMPLETE,
                clock.advance(3, mode, DURATION_MS, BASE + 120 * NANOS_PER_MS),
                0.0F);
            Assert.assertTrue("完成后仍保持动画档位语义", clock.isAnimating());
        }
    }

    @Test
    public void frameDropJumpsForwardWithoutOvershoot() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        clock.advance(9, "wave", DURATION_MS, BASE);
        Assert.assertEquals(
            "掉帧后只跳到已完成，不得越界",
            ChainPreviewAnimationClock.COMPLETE,
            clock.advance(9, "wave", DURATION_MS, BASE + 10_000 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(ChainPreviewAnimationClock.COMPLETE, clock.getAnimationU(), 0.0F);
    }

    @Test
    public void clockGoingBackwardsNeverProducesNegativeProgress() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        clock.advance(4, "wave", DURATION_MS, BASE);
        clock.advance(4, "wave", DURATION_MS, BASE + 60 * NANOS_PER_MS);
        Assert.assertEquals(
            "时钟回退必须回到起点而非负值",
            ChainPreviewAnimationClock.START,
            clock.advance(4, "wave", DURATION_MS, BASE - 1000L),
            0.0F);
        Assert.assertEquals(ChainPreviewAnimationClock.START, clock.getAnimationU(), 0.0F);
    }

    @Test
    public void generationChangeRestartsTimeline() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        clock.advance(1, "wave", DURATION_MS, BASE);
        clock.advance(1, "wave", DURATION_MS, BASE + 60 * NANOS_PER_MS);
        Assert.assertEquals(
            "换代必须从起点重新计时",
            ChainPreviewAnimationClock.START,
            clock.advance(2, "wave", DURATION_MS, BASE + 61 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(2, clock.getGeneration());
        Assert.assertEquals(
            "新代按自身起点推进",
            0.5F,
            clock.advance(2, "wave", DURATION_MS, BASE + 61 * NANOS_PER_MS + 60 * NANOS_PER_MS),
            0.0001F);
    }

    @Test
    public void durationOrModeChangeRestartsTimelineInSameGeneration() {
        // Lead 新裁定（6ce6623）：同代内 durationMs 或档位变化，下一帧按新一轮处理（u 归 0 重新计时）。
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        Assert.assertEquals(
            ChainPreviewAnimationClock.START,
            clock.advance(5, "wave", 1000, BASE),
            0.0F);
        Assert.assertEquals(
            0.5F,
            clock.advance(5, "wave", 1000, BASE + 500 * NANOS_PER_MS),
            0.0001F);

        // 同代内 duration 1000 -> 200：变化那一帧必须重置为新时间线起点。
        Assert.assertEquals(
            "同代内时长变化必须重置时间线",
            ChainPreviewAnimationClock.START,
            clock.advance(5, "wave", 200, BASE + 501 * NANOS_PER_MS),
            0.0F);
        // duration 不变：不得重置，按新时间线继续推进。
        Assert.assertEquals(
            "duration 不变时不得重置（继续推进）",
            0.25F,
            clock.advance(5, "wave", 200, BASE + 551 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            "新时长下推进到一半",
            0.5F,
            clock.advance(5, "wave", 200, BASE + 601 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            "恰满新时长即完成",
            ChainPreviewAnimationClock.COMPLETE,
            clock.advance(5, "wave", 200, BASE + 701 * NANOS_PER_MS),
            0.0F);

        // 同代内档位变化（wave -> flow）同样按新一轮处理。
        Assert.assertEquals(
            "同代内档位变化必须重置时间线",
            ChainPreviewAnimationClock.START,
            clock.advance(5, "flow", 200, BASE + 702 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(
            "flow 按新时间线推进",
            0.5F,
            clock.advance(5, "flow", 200, BASE + 802 * NANOS_PER_MS),
            0.0001F);

        // off 档立即完成；再从 off 回到 wave 必须重新计时。
        Assert.assertEquals(
            "off 档立即回到完成",
            ChainPreviewAnimationClock.COMPLETE,
            clock.advance(5, "off", 200, BASE + 803 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(
            "回到 wave 档必须重新计时",
            ChainPreviewAnimationClock.START,
            clock.advance(5, "wave", 200, BASE + 804 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(
            "重启后按新时长推进",
            0.5F,
            clock.advance(5, "wave", 200, BASE + 904 * NANOS_PER_MS),
            0.0001F);
    }

    @Test
    public void progressIsMonotoneWithinGeneration() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        clock.advance(11, "wave", DURATION_MS, BASE);
        float previous = -1.0F;
        for (int step = 0; step <= 40; step++) {
            float u = clock.advance(11, "wave", DURATION_MS, BASE + step * 3L * NANOS_PER_MS);
            Assert.assertTrue("同代内完成度单调不减：" + previous + "->" + u, u >= previous - 0.000001F);
            Assert.assertTrue("完成度必须在 [0,1]", u >= 0.0F && u <= 1.0F);
            previous = u;
        }
        Assert.assertEquals(ChainPreviewAnimationClock.COMPLETE, previous, 0.0F);
    }

    @Test
    public void resetClearsStateAndDoesNotLeakAcrossGenerations() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        clock.advance(21, "wave", DURATION_MS, BASE + 60 * NANOS_PER_MS);
        clock.reset();
        Assert.assertFalse(clock.isAnimating());
        Assert.assertEquals(ChainPreviewAnimationClock.COMPLETE, clock.getAnimationU(), 0.0F);
        Assert.assertEquals(Integer.MIN_VALUE, clock.getGeneration());
        Assert.assertEquals(
            "reset 后新代必须从起点开始",
            ChainPreviewAnimationClock.START,
            clock.advance(22, "wave", DURATION_MS, BASE + 200 * NANOS_PER_MS),
            0.0F);
    }
}
