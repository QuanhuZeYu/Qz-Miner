package club.heiqi.qz_miner.client;

import java.util.List;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeBootstrap;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeBootstrap;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.uilib.ui.scene.layout.FlexDirection;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.SceneChromeTokens;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;

/**
 * 回归 4.9 虚拟窗口契约：scene 内容树结构、空内容整窗隐藏、signal 冲刷与样式约束。
 *
 * <p>headless 使用真实 {@link SceneRuntime}：窗口工厂只建树，物化由 runtime.flush() 驱动。</p>
 */
public class QzMinerHudWindowTest {

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
    }

    @Test
    public void contentTreeIsSceneRowsWithToneColoredSpans() {
        Fixture fixture = new Fixture();
        fixture.openGate(ChainPhase.RUNNING);
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        fixture.state.setRequestedChainRadius(7);
        fixture.state.setServerChainRadius(5);
        fixture.state.setRequestedChainMaxBlocks(99);
        fixture.state.setServerChainMaxBlocks(80);

        SceneRuntime runtime = new SceneRuntime();
        SceneNode content = fixture.window.build(runtime);
        fixture.window.refresh();
        runtime.flush();

        Assert.assertEquals(FlexDirection.COLUMN, content.getFlexDirection());
        Assert.assertEquals(QzMinerHudWindow.ROW_GAP_PX, content.getGap());
        Assert.assertFalse(content.isHitTestable());

        List<SceneNode> rows = content.__getChildren();
        Assert.assertEquals(6, rows.size());
        for (SceneNode row : rows) {
            Assert.assertEquals(FlexDirection.ROW, row.getFlexDirection());
            Assert.assertEquals(QzMinerHudWindow.SPAN_GAP_PX, row.getGap());
            Assert.assertFalse(row.isHitTestable());
            for (SceneNode span : row.__getChildren()) {
                Assert.assertEquals(QzMinerHudWindow.FONT_SIZE_PX, span.getFontSize());
                Assert.assertFalse(span.isHitTestable());
            }
        }

        List<SceneNode> statusSpans = rows.get(0).__getChildren();
        Assert.assertEquals(2, statusSpans.size());
        Assert.assertEquals(ClientI18n.tr("hud.qz_miner.status.label") + " ", statusSpans.get(0).getText());
        Assert.assertEquals(SceneChromeTokens.HUD_TEXT_MUTED, statusSpans.get(0).getTextColor());
        Assert.assertEquals(ClientI18n.tr("hud.qz_miner.status.running"), statusSpans.get(1).getText());
        Assert.assertEquals(SceneChromeTokens.HUD_TEXT_SUCCESS, statusSpans.get(1).getTextColor());

        List<SceneNode> configSpans = rows.get(3).__getChildren();
        Assert.assertEquals(5, configSpans.size());
        Assert.assertEquals("7/5", configSpans.get(1).getText());
        Assert.assertEquals(SceneChromeTokens.HUD_TEXT_INFO, configSpans.get(1).getTextColor());
        Assert.assertEquals(ClientI18n.tr("hud.qz_miner.separator") + " ", configSpans.get(2).getText());
        Assert.assertEquals(SceneChromeTokens.HUD_TEXT_MUTED, configSpans.get(2).getTextColor());
        Assert.assertEquals("99/80", configSpans.get(4).getText());
        Assert.assertEquals(SceneChromeTokens.HUD_TEXT_INFO, configSpans.get(4).getTextColor());
    }

    @Test
    public void emptyModelKeepsContentTreeHiddenUntilGateOpens() {
        Fixture fixture = new Fixture();
        SceneRuntime runtime = new SceneRuntime();
        SceneNode content = fixture.window.build(runtime);
        fixture.window.refresh();
        runtime.flush();
        Assert.assertTrue("显示门关闭 => 内容树零尺寸（宿主整窗隐藏）",
                content.__getChildren().isEmpty());

        fixture.openGate(ChainPhase.PLANNING);
        fixture.window.refresh();
        runtime.flush();
        Assert.assertFalse("显示门打开 => 内容树出现", content.__getChildren().isEmpty());

        fixture.closeGate();
        fixture.window.refresh();
        runtime.flush();
        Assert.assertTrue("显示门再次关闭 => 内容整树卸载", content.__getChildren().isEmpty());
    }

    @Test
    public void refreshPublishesOnlyChangedModels() {
        Fixture fixture = new Fixture();
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        fixture.openGate(ChainPhase.RUNNING);
        SceneRuntime runtime = new SceneRuntime();
        SceneNode content = fixture.window.build(runtime);
        fixture.window.refresh();
        runtime.flush();

        QzMinerHudModel first = fixture.window.currentModel();
        Assert.assertFalse(first.isEmpty());
        fixture.window.refresh();
        Assert.assertSame("状态未变不得重发 signal", first, fixture.window.currentModel());

        fixture.state.setServerMatchedTargetCount(31);
        fixture.window.refresh();
        Assert.assertNotSame(first, fixture.window.currentModel());
        runtime.flush();
        Assert.assertEquals("31", rows(content).get(4).__getChildren().get(1).getText());
    }

    @Test
    public void conditionalRowsAppearAndDisappearInSceneTree() {
        Fixture fixture = new Fixture();
        fixture.state.setSelectedMode(ChainMode.AREA);
        fixture.state.setSelectedSubMode(ChainSubMode.AREA_TUNNEL);
        fixture.openGate(ChainPhase.RUNNING);
        fixture.state.setPreviewActive(true);
        fixture.preview.begin(new ChainTarget(0, 0, 0));
        fixture.preview.addPreviewTarget(
                fixture.preview.getGeneration(), new ChainTarget(1, 0, 0));

        SceneRuntime runtime = new SceneRuntime();
        SceneNode content = fixture.window.build(runtime);
        fixture.window.refresh();
        runtime.flush();

        List<SceneNode> rows = rows(content);
        Assert.assertEquals(8, rows.size());
        Assert.assertEquals(ClientI18n.tr("hud.qz_miner.preview_matched.label") + " ",
                rows.get(rows.size() - 2).__getChildren().get(0).getText());
        Assert.assertEquals(ClientI18n.tr("hud.qz_miner.server_area.label") + " ",
                rows.get(rows.size() - 1).__getChildren().get(0).getText());

        fixture.state.setPreviewActive(false);
        fixture.window.refresh();
        runtime.flush();
        Assert.assertEquals(7, rows(content).size());

        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.window.refresh();
        runtime.flush();
        Assert.assertEquals(6, rows(content).size());
    }

    @Test
    public void sceneContentCarriesNoSectionStyle() {
        Fixture fixture = new Fixture();
        fixture.state.setSelectedMode(ChainMode.AREA);
        fixture.state.setSelectedSubMode(ChainSubMode.AREA_TUNNEL);
        fixture.openGate(ChainPhase.RUNNING);
        fixture.state.setPreviewActive(true);
        fixture.preview.begin(new ChainTarget(0, 0, 0));

        SceneRuntime runtime = new SceneRuntime();
        SceneNode content = fixture.window.build(runtime);
        fixture.window.refresh();
        runtime.flush();

        assertNoSectionStyle(content);
    }

    private static List<SceneNode> rows(SceneNode content) {
        List<SceneNode> rows = content.__getChildren();
        Assert.assertFalse("内容树必须已物化", rows.isEmpty());
        return rows;
    }

    private static void assertNoSectionStyle(SceneNode node) {
        String text = node.getText();
        Assert.assertFalse("scene 文本不得携带旧版样式编码",
                text != null && text.indexOf('\u00a7') >= 0);
        for (SceneNode child : node.__getChildren()) {
            assertNoSectionStyle(child);
        }
    }

    private static final class Fixture {
        private final ChainClientState state = new ChainClientState();
        private final ClientPhaseProjection projection = new ClientPhaseProjection();
        private final ChainPreviewState preview = new ChainPreviewState();
        private final QzMinerHudWindow window = new QzMinerHudWindow(state, projection,
                new QzMinerHudModel.PreviewStateSource() {
                    @Override
                    public ChainPreviewState current() {
                        return preview;
                    }
                });
        private int generation = 1;

        private void openGate(ChainPhase phase) {
            projection.update(phase, generation++, 0L);
            state.setChainKeyPressed(true);
        }

        private void closeGate() {
            projection.update(ChainPhase.IDLE, generation++, 0L);
            state.setChainKeyPressed(false);
        }
    }
}
