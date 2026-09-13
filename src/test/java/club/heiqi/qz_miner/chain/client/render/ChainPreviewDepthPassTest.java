package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;
import org.lwjgl.opengl.GL11;

public class ChainPreviewDepthPassTest {

    @Test
    public void selectMapsThreeChannelsAndFallsBackToXray() {
        Assert.assertEquals(ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.XRAY));
        Assert.assertEquals(ChainPreviewDepthPass.Pass.OCCLUDE,
            ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.OCCLUDE));
        Assert.assertEquals(ChainPreviewDepthPass.Pass.OUTLINE,
            ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.OUTLINE));
        Assert.assertEquals("null 兜底 xray", ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.select(null));
    }

    @Test
    public void xrayStageEqualsTodaysDepthState() {
        ChainPreviewDepthPass.Stage xray = ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.XRAY, 0);

        Assert.assertFalse("现状关闭深度测试", xray.isDepthTestEnabled());
        Assert.assertFalse("现状 depthMask(false)", xray.isDepthMaskEnabled());
        Assert.assertEquals(ChainPreviewDepthPass.XRAY_STAGE, xray);
        Assert.assertEquals(1, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.XRAY));
    }

    @Test
    public void occludeStageEnablesDepthTestWithLequalAndKeepsDepthMaskOff() {
        ChainPreviewDepthPass.Stage occlude =
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OCCLUDE, 0);

        Assert.assertTrue("occlude 必须开深度测试", occlude.isDepthTestEnabled());
        Assert.assertEquals(GL11.GL_LEQUAL, occlude.getDepthFunc());
        Assert.assertFalse("occlude 不写深度", occlude.isDepthMaskEnabled());
        Assert.assertEquals(1, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.OCCLUDE));
    }

    @Test
    public void outlineRunsShellThenMainInFixedOrder() {
        Assert.assertEquals(2, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.OUTLINE));

        ChainPreviewDepthPass.Stage shell =
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 0);
        ChainPreviewDepthPass.Stage main =
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 1);

        Assert.assertEquals("首段必须是描边壳（沿用历史置顶配方）",
            ChainPreviewDepthPass.OUTLINE_SHELL_STAGE, shell);
        Assert.assertEquals("历史名与壳段必须同配方",
            ChainPreviewDepthPass.OUTLINE_OVERLAY_STAGE, shell);
        Assert.assertEquals("末段必须是主体配方", ChainPreviewDepthPass.OUTLINE_MAIN_STAGE, main);
        Assert.assertFalse("壳段关深测（全可见）", shell.isDepthTestEnabled());
        Assert.assertTrue("主体段开深测（自遮挡正确）", main.isDepthTestEnabled());
        Assert.assertEquals(GL11.GL_LEQUAL, main.getDepthFunc());
        Assert.assertFalse(shell.isDepthMaskEnabled());
        Assert.assertFalse(main.isDepthMaskEnabled());
    }

    @Test
    public void outlineShellRoleIsPreciseAndMatchesStageIndexing() {
        Assert.assertTrue(ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OUTLINE, 0));
        Assert.assertFalse(ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OUTLINE, 1));
        Assert.assertFalse(ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OUTLINE, 5));
        Assert.assertTrue("越界口径与 stage() 一致：负索引按首段处理",
            ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OUTLINE, -3));
        Assert.assertSame(ChainPreviewDepthPass.OUTLINE_SHELL_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, -3));
        Assert.assertFalse(ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.XRAY, 0));
        Assert.assertFalse(ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OCCLUDE, 0));
        Assert.assertFalse(ChainPreviewDepthPass.isOutlineShellStage(null, 0));
    }

    @Test
    public void xrayAndOccludeKeepHistoricalSingleStageRecipes() {
        Assert.assertEquals(1, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.XRAY));
        Assert.assertEquals(1, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.OCCLUDE));
        Assert.assertSame(ChainPreviewDepthPass.XRAY_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.XRAY, 0));
        Assert.assertSame(ChainPreviewDepthPass.OCCLUDE_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OCCLUDE, 0));
        Assert.assertFalse(ChainPreviewDepthPass.isOutlineShellStage(ChainPreviewDepthPass.Pass.OCCLUDE, 99));
    }

    @Test
    public void stageIndexOutOfRangeIsDefensiveAndNeverThrows() {
        Assert.assertEquals("越界索引返回该档最后一次绘制的配方（主体）",
            ChainPreviewDepthPass.OUTLINE_MAIN_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 9));
        Assert.assertEquals(ChainPreviewDepthPass.XRAY_STAGE,
            ChainPreviewDepthPass.stage(null, 3));
        Assert.assertEquals(ChainPreviewDepthPass.OCCLUDE_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OCCLUDE, 5));
    }
}
