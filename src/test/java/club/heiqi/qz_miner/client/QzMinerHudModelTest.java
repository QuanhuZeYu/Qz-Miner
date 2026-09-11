package club.heiqi.qz_miner.client;

import java.util.ArrayList;
import java.util.Arrays;
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

/** 回归 4.9 显示模型的显示门、原信息行、稳定行/片段标识与色调语义。 */
public class QzMinerHudModelTest {

    @BeforeClass
    public static void bootstrapModes() {
        ChainModeBootstrap.bootstrap();
        ChainSubModeBootstrap.bootstrap();
    }

    @Test
    public void visibilityFollowsKeyAndActiveExecutionPhases() {
        Fixture fixture = new Fixture();
        assertVisible(fixture, ChainPhase.IDLE, false, false);
        assertVisible(fixture, ChainPhase.ARMED, false, false);
        assertVisible(fixture, ChainPhase.PLANNING, false, true);
        assertVisible(fixture, ChainPhase.RUNNING, false, true);
        assertVisible(fixture, ChainPhase.FINISHING, false, true);
        assertVisible(fixture, ChainPhase.IDLE, true, true);
        assertVisible(fixture, ChainPhase.ARMED, true, true);
    }

    @Test
    public void baseLinesKeepLegacyInformationAndOrder() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        fixture.state.setRequestedChainRadius(7);
        fixture.state.setServerChainRadius(5);
        fixture.state.setRequestedChainMaxBlocks(99);
        fixture.state.setServerChainMaxBlocks(80);
        fixture.state.setServerMatchedTargetCount(12);

        Assert.assertEquals(Arrays.asList("status", "mode", "sub-mode", "chain-config",
                "server-matched", "object-group-sync"), ids(fixture.model()));
    }

    @Test
    public void previewPrecedesAreaAndBothAreConditional() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.AREA);
        fixture.state.setSelectedSubMode(ChainSubMode.AREA_TUNNEL);
        fixture.state.setPreviewActive(true);
        fixture.preview.begin(new ChainTarget(0, 0, 0));
        fixture.preview.addPreviewTarget(
                fixture.preview.getGeneration(), new ChainTarget(1, 0, 0));

        List<String> ids = ids(fixture.model());
        Assert.assertEquals("preview-matched", ids.get(ids.size() - 2));
        Assert.assertEquals("server-area", ids.get(ids.size() - 1));

        fixture.state.setPreviewActive(false);
        Assert.assertFalse(ids(fixture.model()).contains("preview-matched"));
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        Assert.assertFalse(ids(fixture.model()).contains("server-area"));
    }

    @Test
    public void spansExposeBusinessValuesAndTonesWithStableIds() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        fixture.state.setRequestedChainRadius(7);
        fixture.state.setServerChainRadius(5);
        fixture.state.setRequestedChainMaxBlocks(99);
        fixture.state.setServerChainMaxBlocks(80);
        fixture.state.setServerMatchedTargetCount(12);

        Assert.assertEquals("hud.qz_miner.mode.chain", ChainMode.CHAIN.getDisplayNameKey());
        Assert.assertEquals("hud.qz_miner.sub_mode.chain.ore", ChainSubMode.CHAIN_ORE.getDisplayNameKey());

        QzMinerHudModel model = fixture.model();
        assertSpan(model, "mode", "mode.value", ClientI18n.tr("hud.qz_miner.mode.chain"),
                QzMinerHudModel.Tone.INFO);
        assertSpan(model, "sub-mode", "sub-mode.value", ClientI18n.tr("hud.qz_miner.sub_mode.chain.ore"),
                QzMinerHudModel.Tone.INFO);
        assertSpan(model, "chain-config", "chain-config.radius-value", "7/5",
                QzMinerHudModel.Tone.INFO);
        assertSpan(model, "chain-config", "chain-config.blocks-value", "99/80",
                QzMinerHudModel.Tone.INFO);
        assertSpan(model, "chain-config", "chain-config.separator",
                ClientI18n.tr("hud.qz_miner.separator") + " ", QzMinerHudModel.Tone.MUTED);
        assertSpan(model, "server-matched", "server-matched.value", "12",
                QzMinerHudModel.Tone.INFO);
        assertSpan(model, "object-group-sync", "object-group-sync.state",
                ClientI18n.tr("hud.qz_miner.sync.pending"), QzMinerHudModel.Tone.WARNING);
        assertNoLegacySectionStyle(model);
    }

    @Test
    public void previewAndAreaExposeValueAndStateTones() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setSelectedMode(ChainMode.AREA);
        fixture.state.setSelectedSubMode(ChainSubMode.AREA_TUNNEL);
        fixture.state.setPreviewActive(true);
        fixture.preview.begin(new ChainTarget(0, 0, 0));
        fixture.preview.addPreviewTarget(
                fixture.preview.getGeneration(), new ChainTarget(1, 0, 0));

        QzMinerHudModel calculating = fixture.model();
        assertSpan(calculating, "preview-matched", "preview-matched.value", "1",
                QzMinerHudModel.Tone.INFO);
        assertSpan(calculating, "preview-matched", "preview-matched.state",
                ClientI18n.tr("hud.qz_miner.preview.calculating"), QzMinerHudModel.Tone.WARNING);
        assertSpan(calculating, "server-area", "server-area.volume", "72",
                QzMinerHudModel.Tone.INFO);

        fixture.preview.setCompleted(fixture.preview.getGeneration(), true);
        assertSpan(fixture.model(), "preview-matched", "preview-matched.state",
                ClientI18n.tr("hud.qz_miner.preview.completed"), QzMinerHudModel.Tone.SUCCESS);
    }

    @Test
    public void previewTranslationIgnoresDisplayGate() {
        Fixture fixture = new Fixture();
        fixture.state.setSelectedMode(ChainMode.CHAIN);
        fixture.state.setSelectedSubMode(ChainSubMode.CHAIN_ORE);
        fixture.state.setServerMatchedTargetCount(12);

        // 门关闭（连锁键未按、阶段 IDLE）：HUD 翻译为空，编辑预览仍有完整卡片内容。
        Assert.assertTrue(fixture.model().isEmpty());
        QzMinerHudModel preview = fixture.previewModel();
        Assert.assertFalse("预览模型不跟随显示门（编辑会话里门必然关闭）", preview.isEmpty());
        Assert.assertEquals(Arrays.asList("status", "mode", "sub-mode", "chain-config",
                "server-matched", "object-group-sync"), ids(preview));
        assertSpan(preview, "status", "status.value", ClientI18n.tr("hud.qz_miner.status.idle"),
                QzMinerHudModel.Tone.WARNING);
        assertSpan(preview, "mode", "mode.value", ClientI18n.tr("hud.qz_miner.mode.chain"),
                QzMinerHudModel.Tone.INFO);
        assertSpan(preview, "server-matched", "server-matched.value", "12",
                QzMinerHudModel.Tone.INFO);
        assertNoLegacySectionStyle(preview);

        // 门打开时预览与 HUD 同源（行序一致），预览只是不把门当条件。
        fixture.state.setChainKeyPressed(true);
        Assert.assertEquals(ids(fixture.model()), ids(preview));
    }

    @Test
    public void modelIsImmutableAndEqualitySupportsSignalDeduplication() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);

        QzMinerHudModel first = fixture.model();
        QzMinerHudModel second = fixture.model();
        Assert.assertNotSame("每次翻译产生独立值对象", first, second);
        Assert.assertEquals("同状态翻译结果必须相等（signal 去重依据）", first, second);

        try {
            first.getLines().add(first.getLines().get(0));
            Assert.fail("model lines must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 4.9 显示模型为不可变值对象。
        }
        try {
            first.getLines().get(0).getSpans().add(first.getLines().get(0).getSpans().get(0));
            Assert.fail("line spans must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 同上。
        }

        fixture.state.setServerMatchedTargetCount(31);
        QzMinerHudModel third = fixture.model();
        Assert.assertNotEquals("业务值变化必须产生不等模型", first, third);
    }

    @Test
    public void contentKeysChangeOnlyWhenLineContentChanges() {
        Fixture fixture = new Fixture();
        fixture.state.setChainKeyPressed(true);
        fixture.state.setServerMatchedTargetCount(12);
        QzMinerHudModel first = fixture.model();
        QzMinerHudModel sameValues = fixture.model();
        Assert.assertEquals(contentKey(first, "server-matched"), contentKey(sameValues, "server-matched"));
        Assert.assertEquals(contentKey(first, "mode"), contentKey(sameValues, "mode"));

        fixture.state.setServerMatchedTargetCount(13);
        QzMinerHudModel changed = fixture.model();
        Assert.assertNotEquals(contentKey(first, "server-matched"), contentKey(changed, "server-matched"));
        Assert.assertEquals("未变化的行内容键必须稳定（keyed 列表复用节点）",
                contentKey(first, "mode"), contentKey(changed, "mode"));
    }

    private static void assertVisible(Fixture fixture, ChainPhase phase, boolean key, boolean expected) {
        fixture.projection.update(phase, fixture.generation++, 0L);
        fixture.state.setChainKeyPressed(key);
        Assert.assertEquals(phase + " key=" + key, expected, !fixture.model().isEmpty());
    }

    private static List<String> ids(QzMinerHudModel model) {
        List<String> ids = new ArrayList<String>();
        for (QzMinerHudModel.Line line : model.getLines()) {
            ids.add(line.getId());
        }
        return ids;
    }

    private static String contentKey(QzMinerHudModel model, String lineId) {
        for (QzMinerHudModel.Line line : model.getLines()) {
            if (lineId.equals(line.getId())) {
                return line.contentKey();
            }
        }
        Assert.fail("missing line " + lineId);
        return null;
    }

    private static void assertSpan(QzMinerHudModel model, String lineId, String spanId,
            String text, QzMinerHudModel.Tone tone) {
        for (QzMinerHudModel.Line line : model.getLines()) {
            if (!lineId.equals(line.getId())) {
                continue;
            }
            for (QzMinerHudModel.Span span : line.getSpans()) {
                if (spanId.equals(span.getId())) {
                    Assert.assertEquals(text, span.getText());
                    Assert.assertEquals(tone, span.getTone());
                    return;
                }
            }
        }
        Assert.fail("missing span " + lineId + "/" + spanId);
    }

    private static void assertNoLegacySectionStyle(QzMinerHudModel model) {
        for (QzMinerHudModel.Line line : model.getLines()) {
            for (QzMinerHudModel.Span span : line.getSpans()) {
                Assert.assertFalse("HUD 文本不得携带旧版样式编码",
                        span.getText().indexOf('\u00a7') >= 0);
            }
        }
    }

    private static final class Fixture {
        private final ChainClientState state = new ChainClientState();
        private final ClientPhaseProjection projection = new ClientPhaseProjection();
        private final ChainPreviewState preview = new ChainPreviewState();
        private final QzMinerHudModel.PreviewStateSource source =
                new QzMinerHudModel.PreviewStateSource() {
                    @Override
                    public ChainPreviewState current() {
                        return preview;
                    }
                };
        private int generation = 1;

        private QzMinerHudModel model() {
            return QzMinerHudModel.translate(state, projection, source);
        }

        private QzMinerHudModel previewModel() {
            return QzMinerHudModel.translateForPreview(state, projection, source);
        }
    }
}
