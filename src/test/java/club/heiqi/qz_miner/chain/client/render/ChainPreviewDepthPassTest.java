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
    public void outlineRunsMainThenOverlayInFixedOrder() {
        Assert.assertEquals(2, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.OUTLINE));

        ChainPreviewDepthPass.Stage main =
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 0);
        ChainPreviewDepthPass.Stage overlay =
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 1);

        Assert.assertEquals(ChainPreviewDepthPass.OUTLINE_MAIN_STAGE, main);
        Assert.assertEquals(ChainPreviewDepthPass.OUTLINE_OVERLAY_STAGE, overlay);
        Assert.assertTrue("主体 pass 自遮挡正确", main.isDepthTestEnabled());
        Assert.assertFalse("置顶 pass 全可见", overlay.isDepthTestEnabled());
        Assert.assertFalse(main.isDepthMaskEnabled());
        Assert.assertFalse(overlay.isDepthMaskEnabled());
    }

    @Test
    public void stageIndexOutOfRangeIsDefensiveAndNeverThrows() {
        Assert.assertEquals(ChainPreviewDepthPass.OUTLINE_OVERLAY_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 9));
        Assert.assertEquals(ChainPreviewDepthPass.XRAY_STAGE,
            ChainPreviewDepthPass.stage(null, 3));
        Assert.assertEquals(ChainPreviewDepthPass.OCCLUDE_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OCCLUDE, 5));
    }
}
