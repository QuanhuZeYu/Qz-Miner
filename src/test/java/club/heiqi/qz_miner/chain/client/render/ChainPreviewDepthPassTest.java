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

    /**
     * 修复回归锁：描边宽度为 0 时 OUTLINE 不得再走 2 段。
     *
     * <p>壳与主体在宽度 0 时是同一份几何（{@code Visuals} 把 {@code outlineShell} 判为 false），
     * 若 stage 仍按 2 段执行，同一处会被 alpha 混合两次（0.78 → 0.9516），
     * 表现为「配置写 0 = 关闭描边、实际把预览画得更实」。收敛后只有 1 段且关深测，
     * 保留 OUTLINE 的「全可见」语义。</p>
     */
    @Test
    public void outlineWithZeroStrokeWidthCollapsesToSingleXrayPass() {
        ChainPreviewDepthPass.Pass zero = ChainPreviewDepthPass.resolvePass(
            ChainPreviewDrawPlan.DepthChannel.OUTLINE, 0.0F);
        Assert.assertEquals("宽度 0 的 OUTLINE 必须收敛为单遍",
            ChainPreviewDepthPass.Pass.XRAY, zero);
        Assert.assertEquals("收敛后只画一段（修复前为 2 段叠色）",
            1, ChainPreviewDepthPass.stageCount(zero));
        Assert.assertEquals("该段沿用置顶配方，保留 OUTLINE 的全可见语义",
            ChainPreviewDepthPass.XRAY_STAGE, ChainPreviewDepthPass.stage(zero, 0));

        Assert.assertEquals("NaN 不得留下两遍叠色的逃逸路径",
            ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.resolvePass(ChainPreviewDrawPlan.DepthChannel.OUTLINE, Float.NaN));
        Assert.assertEquals("负宽度同样收敛",
            ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.resolvePass(ChainPreviewDrawPlan.DepthChannel.OUTLINE, -1.0F));

        ChainPreviewDepthPass.Pass stroked = ChainPreviewDepthPass.resolvePass(
            ChainPreviewDrawPlan.DepthChannel.OUTLINE, 1.5F);
        Assert.assertEquals("正宽度仍是真描边档", ChainPreviewDepthPass.Pass.OUTLINE, stroked);
        Assert.assertEquals("真描边必须保留壳 → 主体两段", 2,
            ChainPreviewDepthPass.stageCount(stroked));

        Assert.assertEquals("宽度不影响其它档位", ChainPreviewDepthPass.Pass.OCCLUDE,
            ChainPreviewDepthPass.resolvePass(ChainPreviewDrawPlan.DepthChannel.OCCLUDE, 0.0F));
        Assert.assertEquals(ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.resolvePass(ChainPreviewDrawPlan.DepthChannel.XRAY, 1.5F));
        Assert.assertEquals("null 通道仍兜底 xray",
            ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.resolvePass(null, 1.5F));
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
