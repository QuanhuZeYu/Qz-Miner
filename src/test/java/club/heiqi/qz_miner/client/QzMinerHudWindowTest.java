package club.heiqi.qz_miner.client;

import java.util.ArrayList;
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
import club.heiqi.uilib.ui.hud.api.HudToolbarSpec;
import club.heiqi.uilib.ui.render.UiBackdrop;
import club.heiqi.uilib.ui.render.UiBackdropEffect;
import club.heiqi.uilib.ui.scene.layout.Constraints;
import club.heiqi.uilib.ui.scene.layout.FlexDirection;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.SceneLayoutEngine;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.SceneChromeTokens;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;

/**
 * 回归 4.9 虚拟窗口契约：液态玻璃卡片内容树、空内容整窗隐藏、signal 冲刷与样式约束。
 *
 * <p>headless 使用真实 {@link SceneRuntime}：窗口工厂只建树，物化由 runtime.flush() 驱动；
 * 「整窗隐藏」按宿主 {@code SceneHudHost.RetainedWindow.isEmptyContent()} 的口径验证——
 * 同源 {@link SceneLayoutEngine} 布局后读内容根 cachedLayout，空内容必须为零尺寸。</p>
 */
public class QzMinerHudWindowTest {

    /** 无头文本度量：足够驱动 SHRINK 宽度与行高收敛，不依赖客户端字体栈。 */
    private static final SceneTextMeasurer MEASURER = new SceneTextMeasurer() {
        @Override
        public int measureWidth(String text, int fontSize) {
            return text.length() * fontSize / 2;
        }

        @Override
        public int lineHeight(int fontSize) {
            return fontSize + 2;
        }

        @Override
        public int epoch() {
            return 0;
        }
    };

    /** 宿主测量口径：逻辑视口尺寸（SceneHudHost.render 用视口宽高做约束）。 */
    private static final int VIEWPORT_WIDTH = 320;
    private static final int VIEWPORT_HEIGHT = 240;

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
    }

    @Test
    public void contentRootWrapsSingleLiquidGlassCard() {
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
        Assert.assertEquals(SceneNode.WidthSizing.SHRINK, content.getWidthSizing());
        Assert.assertFalse(content.isHitTestable());

        SceneNode card = cardOf(content);
        Assert.assertEquals(FlexDirection.COLUMN, card.getFlexDirection());
        Assert.assertEquals(QzMinerHudWindow.ROW_GAP_PX, card.getGap());
        Assert.assertEquals(QzMinerHudWindow.CARD_PADDING_X_PX, card.getPaddingLeft());
        Assert.assertEquals(QzMinerHudWindow.CARD_PADDING_X_PX, card.getPaddingRight());
        Assert.assertEquals(QzMinerHudWindow.CARD_PADDING_Y_PX, card.getPaddingTop());
        Assert.assertEquals(QzMinerHudWindow.CARD_PADDING_Y_PX, card.getPaddingBottom());
        Assert.assertEquals(SceneNode.WidthSizing.SHRINK, card.getWidthSizing());
        Assert.assertFalse(card.isHitTestable());

        // 液态玻璃：公开材质 API + 公开表面绑定落到卡片节点（含圆角，来自主题 PANEL 配方）。
        UiBackdrop backdrop = card.getBackdrop();
        Assert.assertNotNull("卡片必须绑定 UILib 液态玻璃滤镜", backdrop);
        Assert.assertTrue("滤镜必须处于生效态", backdrop.isActive());
        Assert.assertEquals(UiBackdropEffect.Family.LIQUID_GLASS, backdrop.getEffect().getFamily());
        Assert.assertEquals(QzMinerHudWindow.GLASS_MATERIAL, backdrop.getEffect().getMaterial());
        Assert.assertEquals(QzMinerHudWindow.GLASS_BLUR_PX, backdrop.getBlurRadius());
        Assert.assertTrue("卡片圆角来自主题 PANEL 角色", card.getCornerRadius() > 0);

        List<SceneNode> rows = rowsOf(card);
        Assert.assertEquals(6, rows.size());
        for (SceneNode row : rows) {
            Assert.assertEquals(FlexDirection.ROW, row.getFlexDirection());
            Assert.assertEquals(QzMinerHudWindow.SPAN_GAP_PX, row.getGap());
            Assert.assertEquals(SceneNode.WidthSizing.SHRINK, row.getWidthSizing());
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
    public void emptyModelUnmountsCardAndKeepsContentRootZeroSized() {
        Fixture fixture = new Fixture();
        SceneRuntime runtime = new SceneRuntime();
        SceneNode content = fixture.window.build(runtime);
        fixture.window.refresh();
        runtime.flush();
        assertHostHides(content);

        fixture.openGate(ChainPhase.PLANNING);
        fixture.window.refresh();
        runtime.flush();
        Assert.assertEquals("显示门打开 => 卡片挂载", 1, content.__getChildren().size());
        assertHostShows(content);

        fixture.closeGate();
        fixture.window.refresh();
        runtime.flush();
        assertHostHides(content);
    }

    @Test
    public void editPreviewRootIsHitTestableWhileCardStaysReadOnly() {
        Fixture fixture = new Fixture();
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        SceneRuntime runtime = new SceneRuntime();

        // 门关闭（默认状态）：HUD 工厂内容根不可命中；编辑预览根必须可命中，否则拖动 handler 收不到指针事件。
        SceneNode preview = fixture.window.previewFactory().build(runtime);
        Assert.assertNotNull(preview);
        Assert.assertTrue("预览根必须是可命中的拖动命中面", preview.isHitTestable());
        Assert.assertEquals(SceneNode.WidthSizing.SHRINK, preview.getWidthSizing());

        runtime.flush();
        SceneNode card = cardOf(preview);
        Assert.assertFalse("卡片与文本在编辑期仍不可命中（只读展示语义不变）", card.isHitTestable());
        Assert.assertNotNull("预览卡片同样走公开液态玻璃材质", card.getBackdrop());
        Assert.assertEquals(UiBackdropEffect.Family.LIQUID_GLASS,
                card.getBackdrop().getEffect().getFamily());
        List<SceneNode> rows = rowsOf(card);
        Assert.assertEquals("预览不跟随显示门：门关闭也必须有完整内容", 6, rows.size());
        for (SceneNode row : rows) {
            Assert.assertFalse(row.isHitTestable());
            for (SceneNode span : row.__getChildren()) {
                Assert.assertFalse(span.isHitTestable());
            }
        }

        // 可拖动的前提是预览有非零面积（宿主按内容盒英寸测量 + clamp）。
        LayoutBox box = layoutOf(preview);
        Assert.assertTrue("预览必须有可拖动面积，实际 " + box, box.getWidth() > 0);
        Assert.assertTrue("预览必须有可拖动面积，实际 " + box, box.getHeight() > 0);

        SceneRuntime hudRuntime = new SceneRuntime();
        SceneNode hud = fixture.window.build(hudRuntime);
        Assert.assertFalse("关闭态 HUD 内容根不得拦截玩家输入", hud.isHitTestable());
    }

    @Test
    public void refreshPublishesOnlyChangedModelsAndReusesStableNodes() {
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

        SceneNode card = cardOf(content);
        // __getChildren() 返回内部 children 的不可变视图（applyChildReconcile 原地改写），
        // 行复用断言必须先快照，否则读到的永远是当前子序列。
        List<SceneNode> rowsBefore = new ArrayList<SceneNode>(rowsOf(card));
        fixture.state.setServerMatchedTargetCount(31);
        fixture.window.refresh();
        Assert.assertNotSame(first, fixture.window.currentModel());
        runtime.flush();

        Assert.assertSame("内容变化不得重建卡片（键稳定复用）", card, cardOf(content));
        List<SceneNode> rowsAfter = new ArrayList<SceneNode>(rowsOf(card));
        Assert.assertSame("未变化的行复用既有节点", rowsBefore.get(0), rowsAfter.get(0));
        Assert.assertNotSame("变化行按内容键重建", rowsBefore.get(4), rowsAfter.get(4));
        Assert.assertEquals("31", rowsAfter.get(4).__getChildren().get(1).getText());
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

        SceneNode card = cardOf(content);
        List<SceneNode> rows = rowsOf(card);
        Assert.assertEquals(8, rows.size());
        Assert.assertEquals(ClientI18n.tr("hud.qz_miner.preview_matched.label") + " ",
                rows.get(rows.size() - 2).__getChildren().get(0).getText());
        Assert.assertEquals(ClientI18n.tr("hud.qz_miner.server_area.label") + " ",
                rows.get(rows.size() - 1).__getChildren().get(0).getText());

        fixture.state.setPreviewActive(false);
        fixture.window.refresh();
        runtime.flush();
        Assert.assertEquals(7, rowsOf(cardOf(content)).size());

        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.window.refresh();
        runtime.flush();
        Assert.assertEquals(6, rowsOf(cardOf(content)).size());
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

    @Test
    public void toolbarFactoryLeavesScaleButtonsToUiLibPublicLayer() {
        SceneRuntime runtime = new SceneRuntime();
        SceneNode toolbar = QzMinerHudWindow.TOOLBAR_FACTORY.build(runtime);
        Assert.assertNotNull(toolbar);
        Assert.assertTrue("Miner 工具栏只提供空工具槽", toolbar.__getChildren().isEmpty());
        Assert.assertEquals("", toolbar.getText());
        Assert.assertFalse(toolbar.isHitTestable());
        Assert.assertTrue("缩放 -/1:1/+ 必须由 UILib 公共层追加，规格不得关闭",
                HudToolbarSpec.builder().build().isScaleControls());
    }

    private static SceneNode cardOf(SceneNode content) {
        List<SceneNode> cards = content.__getChildren();
        Assert.assertEquals("内容根必须只挂一张卡片", 1, cards.size());
        return cards.get(0);
    }

    private static List<SceneNode> rowsOf(SceneNode card) {
        Assert.assertFalse("卡片必须已物化", card.__getChildren().isEmpty());
        return card.__getChildren();
    }

    /** 宿主 isEmptyContent() 口径：布局后内容根 cachedLayout 任一轴为零 → 整窗隐藏。 */
    private static void assertHostHides(SceneNode content) {
        Assert.assertTrue("显示门关闭 => 卡片整体卸载", content.__getChildren().isEmpty());
        LayoutBox box = layoutOf(content);
        Assert.assertTrue("空内容内容根必须零尺寸（宿主整窗隐藏），实际 " + box, box.getHeight() <= 0);
    }

    private static void assertHostShows(SceneNode content) {
        LayoutBox box = layoutOf(content);
        Assert.assertTrue("有内容内容根必须非零尺寸，实际 " + box, box.getHeight() > 0);
        Assert.assertTrue("有内容内容根必须非零尺寸，实际 " + box, box.getWidth() > 0);
    }

    private static LayoutBox layoutOf(SceneNode content) {
        new SceneLayoutEngine(MEASURER).layout(content, new Constraints(VIEWPORT_WIDTH, VIEWPORT_HEIGHT));
        Object box = content.getCachedLayout();
        Assert.assertTrue("布局后必须产出 LayoutBox", box instanceof LayoutBox);
        return (LayoutBox) box;
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
