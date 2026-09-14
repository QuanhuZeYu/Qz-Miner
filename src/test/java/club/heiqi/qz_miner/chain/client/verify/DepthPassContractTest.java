package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;
import org.lwjgl.opengl.GL11;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass.Pass;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDepthPass.Stage;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;

/**
 * T17 深度分层 pass 独立契约探针（T15 + 预览渲染契约 draw plan 契约）。
 *
 * <p>独立口径来自 temp/chain-preview/verify/wave3_model.json：三档 → pass 选择表、
 * null/未知兜底 XRAY、xray 单 pass 且深度状态等于历史基线、occlude 开深测不写深度、
 * outline 两段固定顺序（主体 → 置顶）；只验证选择与顺序契约，不假定描边效果存在。</p>
 */
public class DepthPassContractTest {

    @Test
    public void selectMapsEveryChannelAndNullToXray() {
        Assert.assertEquals(
            Pass.XRAY, ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.XRAY));
        Assert.assertEquals(
            Pass.OCCLUDE, ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.OCCLUDE));
        Assert.assertEquals(
            Pass.OUTLINE, ChainPreviewDepthPass.select(ChainPreviewDrawPlan.DepthChannel.OUTLINE));
        Assert.assertEquals("null 必须兜底 XRAY", Pass.XRAY, ChainPreviewDepthPass.select(null));
        for (ChainPreviewDrawPlan.DepthChannel channel : ChainPreviewDrawPlan.DepthChannel.values()) {
            Assert.assertNotNull("任何已知档位都不得返回 null", ChainPreviewDepthPass.select(channel));
        }
    }

    @Test
    public void xraySinglePassMatchesHistoricalDepthState() {
        Assert.assertEquals(1, ChainPreviewDepthPass.stageCount(Pass.XRAY));
        Stage stage = ChainPreviewDepthPass.stage(Pass.XRAY, 0);
        Assert.assertFalse("xray 必须关闭深度测试（= 现状）", stage.isDepthTestEnabled());
        Assert.assertFalse("xray 不得写深度（depthMask false）", stage.isDepthMaskEnabled());
        Assert.assertSame(
            "同档位必须复用同一 Stage 实例（零分配）",
            ChainPreviewDepthPass.XRAY_STAGE,
            stage);
        Assert.assertEquals(
            "越界索引也必须落在同一配方",
            ChainPreviewDepthPass.XRAY_STAGE,
            ChainPreviewDepthPass.stage(Pass.XRAY, 17));
    }

    @Test
    public void occludeEnablesDepthTestWithoutWritingDepth() {
        Assert.assertEquals(1, ChainPreviewDepthPass.stageCount(Pass.OCCLUDE));
        Stage stage = ChainPreviewDepthPass.stage(Pass.OCCLUDE, 0);
        Assert.assertTrue("occlude 必须开启深度测试", stage.isDepthTestEnabled());
        Assert.assertFalse("occlude 不得写深度（避免污染世界深度缓冲）", stage.isDepthMaskEnabled());
        Assert.assertEquals("比较函数必须为 LEQUAL", GL11.GL_LEQUAL, stage.getDepthFunc());
        Assert.assertSame(ChainPreviewDepthPass.OCCLUDE_STAGE, stage);
        Assert.assertSame(
            ChainPreviewDepthPass.OCCLUDE_STAGE,
            ChainPreviewDepthPass.stage(Pass.OCCLUDE, 99));
    }

    @Test
    public void outlineIsShellThenMainInFixedOrder() {
        // B3.x 真描边（Lead 批准方案 A）：段序 = [描边壳（沿用置顶/关深测配方）, 主体]
        Assert.assertEquals(2, ChainPreviewDepthPass.stageCount(Pass.OUTLINE));
        Stage shell = ChainPreviewDepthPass.stage(Pass.OUTLINE, 0);
        Stage main = ChainPreviewDepthPass.stage(Pass.OUTLINE, 1);
        Assert.assertSame("首段必须是描边壳配方（沿用置顶/关深测）",
            ChainPreviewDepthPass.OUTLINE_OVERLAY_STAGE, shell);
        Assert.assertSame("末段必须是主体配方", ChainPreviewDepthPass.OUTLINE_MAIN_STAGE, main);
        Assert.assertFalse("描边壳必须关深测（外扩壳全可见成环）", shell.isDepthTestEnabled());
        Assert.assertTrue("主体段必须开深测（自遮挡正确）", main.isDepthTestEnabled());
        Assert.assertFalse("两段都不得写深度", shell.isDepthMaskEnabled() || main.isDepthMaskEnabled());
        Assert.assertNotEquals("两段必须是不同配方，否则顺序无意义", shell, main);
        // 越界索引取最后一段（防御，不抛）；负索引至少不得抛异常。
        Assert.assertSame(ChainPreviewDepthPass.OUTLINE_MAIN_STAGE,
            ChainPreviewDepthPass.stage(Pass.OUTLINE, 5));
        Assert.assertNotNull(ChainPreviewDepthPass.stage(Pass.OUTLINE, -3));
    }

    @Test
    public void passCountsMatchSelectedPassForEveryChannel() {
        for (ChainPreviewDrawPlan.DepthChannel channel : ChainPreviewDrawPlan.DepthChannel.values()) {
            Pass pass = ChainPreviewDepthPass.select(channel);
            int expected = pass == Pass.OUTLINE ? 2 : 1;
            Assert.assertEquals("channel=" + channel, expected, ChainPreviewDepthPass.stageCount(pass));
            Stage last = ChainPreviewDepthPass.stage(pass, ChainPreviewDepthPass.stageCount(pass) - 1);
            Assert.assertNotNull("最后一段必须存在配方", last);
            for (int index = 0; index < ChainPreviewDepthPass.stageCount(pass); index++) {
                Assert.assertNotNull(
                    "channel=" + channel + " index=" + index,
                    ChainPreviewDepthPass.stage(pass, index));
            }
        }
    }

    @Test
    public void defaultPlanResolvesToFrozenXrayStage() {
        ChainPreviewMesh mesh = new ChainPreviewMeshBuilder().build(
            VerifyShapes.line(2),
            new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F));
        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh, 0, mesh.getIndexCount(), null, null,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(
            "默认档深度通道必须仍是 XRAY（回退到今天）",
            ChainPreviewDrawPlan.DepthChannel.XRAY,
            plan.getDepthChannel());
        Assert.assertEquals(Pass.XRAY, ChainPreviewDepthPass.select(plan.getDepthChannel()));
        Assert.assertEquals(1, ChainPreviewDepthPass.stageCount(ChainPreviewDepthPass.select(plan.getDepthChannel())));
        Assert.assertSame(
            ChainPreviewDepthPass.XRAY_STAGE,
            ChainPreviewDepthPass.stage(ChainPreviewDepthPass.select(plan.getDepthChannel()), 0));
    }

    @Test
    public void selectAndStageArePureAcrossRepeatedCalls() {
        for (ChainPreviewDrawPlan.DepthChannel channel : ChainPreviewDrawPlan.DepthChannel.values()) {
            Pass first = ChainPreviewDepthPass.select(channel);
            for (int repeat = 0; repeat < 5; repeat++) {
                Assert.assertSame("纯函数必须稳定返回同一枚举", first, ChainPreviewDepthPass.select(channel));
            }
            for (int index = 0; index < 4; index++) {
                Assert.assertSame(
                    "同 (pass,index) 必须复用同一 Stage",
                    ChainPreviewDepthPass.stage(first, index),
                    ChainPreviewDepthPass.stage(first, index));
            }
        }
    }
}
