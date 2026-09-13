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
    public void switchingModeOrDurationRestartsTimeline() {
        ChainPreviewAnimationClock clock = new ChainPreviewAnimationClock();
        clock.advance(5, "wave", DURATION_MS, BASE);
        clock.advance(5, "wave", DURATION_MS, BASE + 60 * NANOS_PER_MS);
        // 已登记行为：同代内改 durationMs 不重启时间线（只按 generation 重启），
        // 新时长在下一代/下次启动时生效——配置热改时长会有「本代不生效」的滞后，已上报 Lead。
        Assert.assertEquals(
            "同代内 duration 变化不重启，按原时间线继续",
            61.0F / 120.0F,
            clock.advance(5, "wave", 300, BASE + 61 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            "off 档立即回到完成",
            ChainPreviewAnimationClock.COMPLETE,
            clock.advance(5, "off", 300, BASE + 62 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(
            "回到 wave 档必须重新计时",
            ChainPreviewAnimationClock.START,
            clock.advance(5, "wave", 300, BASE + 63 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(
            "重启后按新时长推进",
            0.5F,
            clock.advance(5, "wave", 300, BASE + 63 * NANOS_PER_MS + 150 * NANOS_PER_MS),
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
