package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewFadeController;

/**
 * T17 淡入淡出 + retiring 独立契约探针（T14）。
 *
 * <p>独立口径来自 工作站 temp/chain-preview/verify/wave3_model.json：
 * off / duration&lt;=0 与历史一致（无过渡、无 retiring）；启用后 FADING_IN → ACTIVE → RETIRING → IDLE；
 * 新代抢占 retiring；时长上限 2000ms；掉帧只跳进；时钟回退不出负值；reset 全清。</p>
 */
public class FadeLifecycleContractTest {

    private static final int DURATION_MS = 120;
    private static final long NANOS_PER_MS = 1_000_000L;
    private static final long BASE = 7_000_000_000L;

    @Test
    public void fadeGateRequiresAnimationModeAndPositiveDuration() {
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("off", DURATION_MS));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled(null, DURATION_MS));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("", DURATION_MS));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("unknown", DURATION_MS));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("flow", 0));
        Assert.assertFalse(ChainPreviewFadeController.isFadeEnabled("wave", -5));
        Assert.assertTrue(ChainPreviewFadeController.isFadeEnabled("flow", DURATION_MS));
        Assert.assertTrue(ChainPreviewFadeController.isFadeEnabled("wave", DURATION_MS));
    }

    @Test
    public void disabledGateKeepsBaselineBehaviourAndNeverRetires() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        Assert.assertEquals(
            "off 档激活必须恒 alpha=1",
            1.0F,
            controller.advance(true, 7, false, 0, BASE),
            0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.ACTIVE, controller.getPhase());
        Assert.assertFalse(controller.isRetiring());
        for (int step = 1; step <= 5; step++) {
            Assert.assertEquals(
                "off 档每帧必须恒 alpha=1",
                1.0F,
                controller.advance(true, 7, false, 0, BASE + step * 100 * NANOS_PER_MS),
                0.0F);
        }
        Assert.assertEquals(
            "off 档结束必须立即 IDLE 且无 retiring（IDLE 防御语义 alpha=0）",
            0.0F,
            controller.advance(false, 7, false, 0, BASE + 1000 * NANOS_PER_MS),
            0.0F);
        Assert.assertTrue(controller.isIdle());
        Assert.assertFalse(controller.isRetiring());
        Assert.assertEquals(
            "off 档 duration 为 0 也必须直通（激活时 alpha=1）",
            1.0F,
            controller.advance(true, 8, false, 0, BASE + 2000 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.ACTIVE, controller.getPhase());
        Assert.assertEquals(
            "off 档再次结束仍返回 IDLE 的 0",
            0.0F,
            controller.advance(false, 8, false, 0, BASE + 3000 * NANOS_PER_MS),
            0.0F);
    }

    @Test
    public void fadeInReachesZeroHalfAndFull() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        Assert.assertEquals(
            "首帧必须从 0 开始",
            0.0F,
            controller.advance(true, 3, true, DURATION_MS, BASE),
            0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.FADING_IN, controller.getPhase());
        Assert.assertEquals(
            0.5F,
            controller.advance(true, 3, true, DURATION_MS, BASE + 60 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            "恰满时长必须到 1",
            1.0F,
            controller.advance(true, 3, true, DURATION_MS, BASE + 120 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.ACTIVE, controller.getPhase());
        Assert.assertEquals(
            "完成后保持 1",
            1.0F,
            controller.advance(true, 3, true, DURATION_MS, BASE + 5000 * NANOS_PER_MS),
            0.0F);
    }

    @Test
    public void retiringFadesFromLastAlphaAndClearsAtTheEnd() {
        ChainPreviewFadeController controller = fullyVisibleController();
        Assert.assertEquals(
            "进入 retiring 的当帧仍保持原 alpha",
            1.0F,
            controller.advance(false, 3, true, DURATION_MS, BASE + 10_000 * NANOS_PER_MS),
            0.0F);
        Assert.assertTrue(controller.isRetiring());
        long retireStart = BASE + 10_000 * NANOS_PER_MS;
        Assert.assertEquals(
            "半程应为起始 alpha 的一半",
            0.5F,
            controller.advance(false, 3, true, DURATION_MS, retireStart + 60 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            "结束时必须归 0 且 IDLE",
            0.0F,
            controller.advance(false, 3, true, DURATION_MS, retireStart + 120 * NANOS_PER_MS),
            0.0F);
        Assert.assertTrue(controller.isIdle());
        Assert.assertFalse(controller.isRetiring());
        // Lead 裁定后的防御语义：IDLE 恒返回 0（不再回弹 1），清空依据仍是 isIdle()/相位。
        Assert.assertEquals(
            "IDLE 必须恒返回 0（防御语义，不得回弹为 1）",
            0.0F,
            controller.advance(false, 3, true, DURATION_MS, retireStart + 5000 * NANOS_PER_MS),
            0.0F);
        Assert.assertTrue("retiring 结束后必须保持 IDLE（调用方清空依据）", controller.isIdle());
        Assert.assertFalse(controller.isRetiring());
        Assert.assertEquals(0.0F, controller.getAlpha(), 0.0F);
    }

    @Test
    public void retiringFromPartialFadeInStartsAtThatAlpha() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        controller.advance(true, 5, true, DURATION_MS, BASE);
        Assert.assertEquals(
            0.5F,
            controller.advance(true, 5, true, DURATION_MS, BASE + 60 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            "从 0.5 淡入态进入 retiring 必须保留该 alpha",
            0.5F,
            controller.advance(false, 5, true, DURATION_MS, BASE + 61 * NANOS_PER_MS),
            0.0001F);
        long retireStart = BASE + 61 * NANOS_PER_MS;
        Assert.assertEquals(
            "retiring 半程必须是起始 alpha 的一半",
            0.25F,
            controller.advance(false, 5, true, DURATION_MS, retireStart + 60 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            0.0F,
            controller.advance(false, 5, true, DURATION_MS, retireStart + 120 * NANOS_PER_MS),
            0.0F);
        Assert.assertTrue(controller.isIdle());
    }

    @Test
    public void newGenerationPreemptsRetiringImmediately() {
        ChainPreviewFadeController controller = fullyVisibleController();
        long retireStart = BASE + 10_000 * NANOS_PER_MS;
        controller.advance(false, 3, true, DURATION_MS, retireStart);
        controller.advance(false, 3, true, DURATION_MS, retireStart + 60 * NANOS_PER_MS);
        Assert.assertTrue(controller.isRetiring());
        Assert.assertEquals(
            "新代必须立即抢占 retiring 并从 0 淡入",
            0.0F,
            controller.advance(true, 4, true, DURATION_MS, retireStart + 61 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.FADING_IN, controller.getPhase());
        Assert.assertEquals(4, controller.getGeneration());
        Assert.assertEquals(
            "新代继续正常淡入",
            0.5F,
            controller.advance(true, 4, true, DURATION_MS, retireStart + 121 * NANOS_PER_MS),
            0.0001F);
    }

    @Test
    public void reActivationDuringRetiringPreemptsEvenWithoutGenerationChange() {
        ChainPreviewFadeController controller = fullyVisibleController();
        long retireStart = BASE + 20_000 * NANOS_PER_MS;
        controller.advance(false, 3, true, DURATION_MS, retireStart);
        controller.advance(false, 3, true, DURATION_MS, retireStart + 60 * NANOS_PER_MS);
        Assert.assertEquals(
            "同代重新激活也必须重新淡入",
            0.0F,
            controller.advance(true, 3, true, DURATION_MS, retireStart + 61 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.FADING_IN, controller.getPhase());
    }

    @Test
    public void durationIsCappedAtTwoSecondsAndFrameDropJumpsForward() {
        Assert.assertEquals(2000, ChainPreviewFadeController.MAX_FADE_MS);
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        controller.advance(true, 1, true, 100000, BASE);
        Assert.assertEquals(
            "硬上限：1500ms 时应为 1500/2000=0.75（而非 1500/100000）",
            0.75F,
            controller.advance(true, 1, true, 100000, BASE + 1500 * NANOS_PER_MS),
            0.0001F);
        Assert.assertEquals(
            "越过上限即完成",
            1.0F,
            controller.advance(true, 1, true, 100000, BASE + 2000 * NANOS_PER_MS),
            0.0F);
        Assert.assertEquals(
            "掉帧后只跳到已完成，不得越界",
            1.0F,
            controller.advance(true, 1, true, 100000, BASE + 10_000_000 * NANOS_PER_MS),
            0.0F);
    }

    @Test
    public void clockBackwardsNeverProducesNegativeAlpha() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        controller.advance(true, 2, true, DURATION_MS, BASE);
        controller.advance(true, 2, true, DURATION_MS, BASE + 60 * NANOS_PER_MS);
        Assert.assertEquals(
            "时钟回退必须归 0 而非负值",
            0.0F,
            controller.advance(true, 2, true, DURATION_MS, BASE - 1000 * NANOS_PER_MS),
            0.0F);
    }

    @Test
    public void resetClearsAllState() {
        ChainPreviewFadeController controller = fullyVisibleController();
        controller.advance(false, 3, true, DURATION_MS, BASE + 30_000 * NANOS_PER_MS);
        controller.reset();
        Assert.assertEquals(ChainPreviewFadeController.Phase.IDLE, controller.getPhase());
        Assert.assertTrue(controller.isIdle());
        Assert.assertFalse(controller.isRetiring());
        Assert.assertEquals("reset 后 IDLE 防御语义 alpha=0", 0.0F, controller.getAlpha(), 0.0F);
        Assert.assertEquals(Integer.MIN_VALUE, controller.getGeneration());
        Assert.assertEquals(
            "reset 后重新激活必须重新淡入",
            0.0F,
            controller.advance(true, 9, true, DURATION_MS, BASE + 40_000 * NANOS_PER_MS),
            0.0F);
    }

    private static ChainPreviewFadeController fullyVisibleController() {
        ChainPreviewFadeController controller = new ChainPreviewFadeController();
        controller.advance(true, 3, true, DURATION_MS, BASE);
        controller.advance(true, 3, true, DURATION_MS, BASE + 120 * NANOS_PER_MS);
        Assert.assertEquals(1.0F, controller.getAlpha(), 0.0F);
        Assert.assertEquals(ChainPreviewFadeController.Phase.ACTIVE, controller.getPhase());
        return controller;
    }
}
