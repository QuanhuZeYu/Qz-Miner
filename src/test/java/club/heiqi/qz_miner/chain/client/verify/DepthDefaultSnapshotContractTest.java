package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;
import org.lwjgl.opengl.GL11;

import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.config.PreviewDepthMode;

/**
 * T39 波次 8 深度档回归锁：xray / occlude 必须逐值等于波次 3 的默认快照。
 *
 * <p>金值直接取自 {@code temp/chain-preview/verify/wave3_model.json} 的 {@code depth_pass_table}
 * （T17 波次 3 独立模型）：xray=(depthTest false, mask false)、occlude=(true, false, LEQUAL)、
 * 未知/null 兜底 xray。OUTLINE 允许在本波次升级为真描边，故只锁「主体段+置顶段」不变量。</p>
 */
public class DepthDefaultSnapshotContractTest {

    private static void assertStage(String label, ChainPreviewDepthPass.Stage stage,
            boolean depthTest, boolean depthMask, int depthFunc) {
        Assert.assertNotNull(label + " stage 不得为 null", stage);
        Assert.assertEquals(label + " depthTest", depthTest, stage.isDepthTestEnabled());
        Assert.assertEquals(label + " depthMask", depthMask, stage.isDepthMaskEnabled());
        if (depthTest) {
            Assert.assertEquals(label + " depthFunc", depthFunc, stage.getDepthFunc());
        }
    }

    @Test
    public void xrayAndOccludeMatchWave3SnapshotExactly() {
        assertStage("xray 常量", ChainPreviewDepthPass.XRAY_STAGE, false, false, GL11.GL_LEQUAL);
        assertStage("occlude 常量", ChainPreviewDepthPass.OCCLUDE_STAGE, true, false, GL11.GL_LEQUAL);

        Assert.assertEquals("xray 通道必须选 XRAY",
            ChainPreviewDepthPass.Pass.XRAY,
            ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.XRAY));
        Assert.assertEquals("occlude 通道必须选 OCCLUDE",
            ChainPreviewDepthPass.Pass.OCCLUDE,
            ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.OCCLUDE));
        Assert.assertEquals("未知/null 通道必须兜底 XRAY（波次 3 快照）",
            ChainPreviewDepthPass.Pass.XRAY, ChainPreviewDepthPass.select(null));

        Assert.assertEquals("xray 必须单段", 1, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.XRAY));
        Assert.assertEquals("occlude 必须单段",
            1, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.OCCLUDE));
        assertStage("xray stage0", ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.XRAY, 0),
            false, false, GL11.GL_LEQUAL);
        assertStage("occlude stage0", ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OCCLUDE, 0),
            true, false, GL11.GL_LEQUAL);
        assertStage("xray 越界索引仍必须是 xray 配方",
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.XRAY, 99), false, false, GL11.GL_LEQUAL);
        assertStage("occlude 越界索引仍必须是 occlude 配方",
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OCCLUDE, 99), true, false, GL11.GL_LEQUAL);
    }

    @Test
    public void defaultSnapshotStillMapsToXrayChannel() {
        Assert.assertEquals("配置默认档必须仍是 xray", "xray", PreviewDepthMode.defaultValue().id());
        Assert.assertEquals("基线视觉快照必须走 xray 通道",
            ChainPreviewDrawPlan.DepthChannel.XRAY,
            ChainPreviewDrawPlan.Visuals.BASELINE.getDepthChannel());
        Assert.assertSame("null 通道必须兜底 XRAY",
            ChainPreviewDepthPass.Pass.XRAY, ChainPreviewDepthPass.select(null));
    }

    @Test
    public void outlineIsShellThenMainKeepingMainRecipeInvariant() {
        // B3.x 真描边（Lead 批准方案 A）：OUTLINE 段序 = [描边壳(关深测), 主体(开深测)]；
        // 这里只锁「壳在首、主体在末」的新契约 + 主体配方不变量，不锁壳的内部参数。
        Assert.assertEquals("OUTLINE 通道必须选 OUTLINE",
            ChainPreviewDepthPass.Pass.OUTLINE,
            ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.OUTLINE));
        int stages = ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.Pass.OUTLINE);
        Assert.assertTrue("OUTLINE 至少两段（描边壳 + 主体）: " + stages, stages >= 2);
        assertStage("OUTLINE 描边壳段", ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 0),
            false, false, GL11.GL_LEQUAL);
        assertStage("OUTLINE 主体段",
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, stages - 1),
            true, false, GL11.GL_LEQUAL);
        assertStage("OUTLINE 越界索引必须返回最后一段（主体）配方",
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.Pass.OUTLINE, 99),
            true, false, GL11.GL_LEQUAL);
    }
}
