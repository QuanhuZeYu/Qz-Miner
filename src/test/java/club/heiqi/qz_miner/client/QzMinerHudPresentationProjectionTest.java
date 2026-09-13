package club.heiqi.qz_miner.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewBackendDiagnostics;
import club.heiqi.qz_miner.chain.client.ChainPreviewState;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.CancelReason;
import club.heiqi.qz_miner.chain.client.ChainPreviewState.TruncationReason;
import club.heiqi.qz_miner.chain.client.projection.ChainPreviewPresentationProjection;
import club.heiqi.qz_miner.chain.client.projection.ClientPhaseProjection;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainClientState;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;

/**
 * HUD 订阅表现投影的 headless 行为契约（task-19b）。
 *
 * <p>覆盖：未装配 installed() 的降级（不出预览行、不抛）；header → 行的字段映射
 * （matched/completed、截断 reason+count、cancelReason）；clientPreviewTruncationSignal
 * 关闭时截断行不展示但投影仍被消费；projection.clear() 后无陈旧行；新文案键中英对称。</p>
 */
public class QzMinerHudPresentationProjectionTest {

    private static final String LANG_ROOT = "assets/qz_miner/lang/";

    private ChainPreviewPresentationProjection previousInstalled;

    /** 每个用例从「未装配」开始，避免静态单例跨用例泄漏。 */
    @Before
    public void setUp() {
        previousInstalled = ChainPreviewPresentationProjection.install(null);
    }

    /** 还原用例前的装配状态。 */
    @After
    public void tearDown() {
        ChainPreviewPresentationProjection.install(previousInstalled);
    }

    /** 未装配投影：HUD 仍出基础行，但不出任何预览行。 */
    @Test
    public void missingInstalledProjectionDegradesWithoutPreviewLines() {
        ChainClientState state = new ChainClientState();
        ClientPhaseProjection phaseProjection = new ClientPhaseProjection();
        state.setChainKeyPressed(true);
        QzMinerHudWindow window = new QzMinerHudWindow(state, phaseProjection);

        window.refresh();
        QzMinerHudModel model = window.currentModel();
        Assert.assertFalse("基础行必须仍在", model.isEmpty());
        Assert.assertFalse(ids(model).contains("preview-matched"));
        Assert.assertFalse(ids(model).contains("preview-truncated"));
        Assert.assertFalse(ids(model).contains("preview-remote-failure"));
        Assert.assertNull(ChainPreviewPresentationProjection.installed());
    }

    /** 装配后：matched/completed 来自 header；未发布 header 时同样降级。 */
    @Test
    public void installedProjectionDrivesPreviewMatchedLineFromHeader() {
        Fixture fixture = new Fixture();
        QzMinerHudWindow window = fixture.newWindow();

        window.refresh();
        Assert.assertFalse("未发布 header 不得出预览行",
                ids(window.currentModel()).contains("preview-matched"));

        int generation = fixture.beginPreview();
        fixture.preview.addPreviewTarget(generation, new ChainTarget(4, 5, 6));
        fixture.publish(false);
        window.refresh();
        assertSpan(window.currentModel(), "preview-matched", "preview-matched.value", "2",
                QzMinerHudModel.Tone.INFO);
        assertSpan(window.currentModel(), "preview-matched", "preview-matched.state",
                ClientI18n.tr("hud.qz_miner.preview.calculating"), QzMinerHudModel.Tone.WARNING);

        Assert.assertTrue(fixture.preview.setCompleted(generation, true));
        fixture.publish(false);
        window.refresh();
        assertSpan(window.currentModel(), "preview-matched", "preview-matched.state",
                ClientI18n.tr("hud.qz_miner.preview.completed"), QzMinerHudModel.Tone.SUCCESS);
    }

    /** 截断行：开关关闭不展示（投影仍被消费），打开后按 reason/counts 映射。 */
    @Test
    public void truncationLineHonoursProjectionSwitchAndMapsReasonAndCounts() {
        Fixture fixture = new Fixture();
        QzMinerHudWindow window = fixture.newWindow();
        int generation = fixture.beginPreview();
        fixture.preview.addPreviewTarget(generation, new ChainTarget(1, 0, 0));
        fixture.preview.addPreviewTarget(generation, new ChainTarget(2, 0, 0));
        Assert.assertTrue(fixture.preview.reportTruncation(
                generation, TruncationReason.MAX_TARGETS, 0, 4096));

        fixture.publish(false);
        window.refresh();
        Assert.assertFalse("clientPreviewTruncationSignal 关闭时截断行不可见",
                ids(window.currentModel()).contains("preview-truncated"));
        Assert.assertTrue("开关关闭也必须继续消费投影（匹配行在）",
                ids(window.currentModel()).contains("preview-matched"));

        fixture.publish(true);
        window.refresh();
        assertSpan(window.currentModel(), "preview-truncated", "preview-truncated.count",
                ClientI18n.tr("hud.qz_miner.preview.truncated.count", "3", "4096"),
                QzMinerHudModel.Tone.WARNING);
        assertSpan(window.currentModel(), "preview-truncated", "preview-truncated.reason",
                ClientI18n.tr("hud.qz_miner.preview.truncated.reason.max_targets"),
                QzMinerHudModel.Tone.WARNING);

        Assert.assertTrue(fixture.preview.reportTruncation(
                generation, TruncationReason.HARD_CAP, 3, 5000));
        fixture.publish(true);
        window.refresh();
        assertSpan(window.currentModel(), "preview-truncated", "preview-truncated.reason",
                ClientI18n.tr("hud.qz_miner.preview.truncated.reason.hard_cap"),
                QzMinerHudModel.Tone.WARNING);

        Assert.assertTrue(fixture.preview.reportTruncation(
                generation, TruncationReason.REMOTE_LIMIT, 1, 5000));
        fixture.publish(true);
        window.refresh();
        assertSpan(window.currentModel(), "preview-truncated", "preview-truncated.reason",
                ClientI18n.tr("hud.qz_miner.preview.truncated.reason.remote_limit"),
                QzMinerHudModel.Tone.WARNING);

        fixture.preview.clear();
        fixture.publish(true);
        window.refresh();
        Assert.assertFalse("预览代清理后不留陈旧截断行",
                ids(window.currentModel()).contains("preview-truncated"));
    }

    /** 执行进度行（B5.2）：投影开关关闭不展示，打开后按 executed/matched 映射。 */
    @Test
    public void executionProgressLineAppearsOnlyWhenProjectionSwitchIsOn() {
        Fixture fixture = new Fixture();
        QzMinerHudWindow window = fixture.newWindow();
        int generation = fixture.beginPreview();
        fixture.preview.addPreviewTarget(generation, new ChainTarget(3, 0, 0));
        fixture.preview.addPreviewTarget(generation, new ChainTarget(4, 0, 0));

        fixture.publish(false, false, 0);
        window.refresh();
        Assert.assertFalse("clientPreviewExecutionProgress 关闭时不得出现执行进度行",
                ids(window.currentModel()).contains("preview-execution-progress"));
        Assert.assertTrue("关闭时匹配行仍来自投影",
                ids(window.currentModel()).contains("preview-matched"));

        fixture.publish(false, true, 2);
        window.refresh();
        assertSpan(window.currentModel(), "preview-execution-progress", "preview-execution-progress.label",
                ClientI18n.tr("hud.qz_miner.preview.progress.label") + " ", QzMinerHudModel.Tone.MUTED);
        assertSpan(window.currentModel(), "preview-execution-progress", "preview-execution-progress.count",
                ClientI18n.tr("hud.qz_miner.preview.progress.count", "2", "3"),
                QzMinerHudModel.Tone.INFO);

        fixture.publish(false, true, 0);
        window.refresh();
        assertSpan(window.currentModel(), "preview-execution-progress", "preview-execution-progress.count",
                ClientI18n.tr("hud.qz_miner.preview.progress.count", "0", "3"),
                QzMinerHudModel.Tone.INFO);
    }

    /** 远端失败行：cancelReason 映射为中英文案，且不受截断开关影响；新一代清除。 */
    @Test
    public void remoteFailureLineMapsCancelReasonAndClearsOnNextGeneration() {
        Fixture fixture = new Fixture();
        QzMinerHudWindow window = fixture.newWindow();
        int generation = fixture.beginPreview();
        fixture.preview.addPreviewTarget(generation, new ChainTarget(3, 3, 3));
        Assert.assertTrue(fixture.preview.cancelPreview(generation, CancelReason.REMOTE_TIMEOUT));

        fixture.publish(false);
        window.refresh();
        assertSpan(window.currentModel(), "preview-remote-failure", "preview-remote-failure.reason",
                ClientI18n.tr("hud.qz_miner.preview.remote.timeout"), QzMinerHudModel.Tone.WARNING);

        int next = fixture.preview.begin(new ChainTarget(7, 7, 7));
        fixture.preview.addPreviewTarget(next, new ChainTarget(8, 8, 8));
        fixture.publish(false);
        window.refresh();
        Assert.assertFalse("新一代开始后旧取消原因不得残留",
                ids(window.currentModel()).contains("preview-remote-failure"));

        Assert.assertTrue(fixture.preview.cancelPreview(next, CancelReason.REMOTE_UNAVAILABLE));
        fixture.publish(false);
        window.refresh();
        assertSpan(window.currentModel(), "preview-remote-failure", "preview-remote-failure.reason",
                ClientI18n.tr("hud.qz_miner.preview.remote.unavailable"),
                QzMinerHudModel.Tone.WARNING);
    }

    /** project.clear()（世界卸载/断线时序）后 HUD 立即降级，直到下一次采样重新发布。 */
    @Test
    public void projectionClearDropsPreviewLinesUntilNextPublish() {
        Fixture fixture = new Fixture();
        QzMinerHudWindow window = fixture.newWindow();
        int generation = fixture.beginPreview();
        fixture.preview.addPreviewTarget(generation, new ChainTarget(9, 9, 9));
        fixture.publish(false);
        window.refresh();
        Assert.assertTrue(ids(window.currentModel()).contains("preview-matched"));

        fixture.projection.clear();
        window.refresh();
        Assert.assertFalse("clear() 后不得读陈旧 header",
                ids(window.currentModel()).contains("preview-matched"));

        fixture.publish(false);
        window.refresh();
        Assert.assertTrue("下一次采样发布后恢复展示",
                ids(window.currentModel()).contains("preview-matched"));
    }

    /** 新增 HUD 文案键在 zh_CN / en_US 都存在且非空（中英对称）。 */
    @Test
    public void newHudLanguageKeysExistInBothLanguages() throws Exception {
        Map<String, String> zh = loadLang("zh_CN.lang");
        Map<String, String> en = loadLang("en_US.lang");
        String[] keys = {
                "hud.qz_miner.preview.truncated.label",
                "hud.qz_miner.preview.truncated.count",
                "hud.qz_miner.preview.truncated.reason.max_targets",
                "hud.qz_miner.preview.truncated.reason.hard_cap",
                "hud.qz_miner.preview.truncated.reason.remote_limit",
                "hud.qz_miner.preview.remote.label",
                "hud.qz_miner.preview.remote.timeout",
                "hud.qz_miner.preview.remote.unavailable",
                "hud.qz_miner.preview.progress.label",
                "hud.qz_miner.preview.progress.count"
        };
        for (String key : keys) {
            Assert.assertTrue("zh 缺少 " + key, zh.containsKey(key));
            Assert.assertTrue("en 缺少 " + key, en.containsKey(key));
            Assert.assertFalse("zh 空值 " + key, zh.get(key).trim().isEmpty());
            Assert.assertFalse("en 空值 " + key, en.get(key).trim().isEmpty());
        }
    }

    private static List<String> ids(QzMinerHudModel model) {
        List<String> ids = new ArrayList<String>();
        for (QzMinerHudModel.Line line : model.getLines()) {
            ids.add(line.getId());
        }
        return ids;
    }

    private static void assertSpan(QzMinerHudModel model, String lineId, String spanId,
            String text, QzMinerHudModel.Tone tone) {
        for (QzMinerHudModel.Line line : model.getLines()) {
            if (!line.getId().equals(lineId)) {
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

    private static Map<String, String> loadLang(String name) throws IOException {
        InputStream in = QzMinerHudPresentationProjectionTest.class.getClassLoader()
                .getResourceAsStream(LANG_ROOT + name);
        Assert.assertNotNull("缺少语言文件 " + name, in);
        Map<String, String> values = new LinkedHashMap<String, String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                if (split > 0) {
                    values.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1));
                }
            }
        } finally {
            reader.close();
        }
        return values;
    }

    /** 用例夹具：装配真实投影，按 tick 语义手动采样。 */
    private static final class Fixture {

        private final ChainClientState state = new ChainClientState();
        private final ClientPhaseProjection phaseProjection = new ClientPhaseProjection();
        private final ChainPreviewState preview = new ChainPreviewState();
        private final ChainPreviewPresentationProjection projection =
                new ChainPreviewPresentationProjection();
        private boolean backendDiagnosticsEnabled;
        private String activeBackendId = "";
        private String backendFallbackReason = "";

        private Fixture() {
            state.setChainKeyPressed(true);
            phaseProjection.update(ChainPhase.RUNNING, 1, 0L);
            ChainPreviewPresentationProjection.install(projection);
        }

        private QzMinerHudWindow newWindow() {
            return new QzMinerHudWindow(state, phaseProjection);
        }

        private int beginPreview() {
            int generation = preview.begin(new ChainTarget(1, 2, 3));
            preview.addPreviewTarget(generation, new ChainTarget(2, 2, 3));
            return generation;
        }

        private void publish(boolean truncationSignalEnabled) {
            publish(truncationSignalEnabled, false, 0);
        }

        /** 设置后端诊断投影位（Q4）；默认关闭，既有断言因此逐字不变。 */
        private void publishBackendDiagnostics(boolean enabled, String backendId, String fallbackReason) {
            this.backendDiagnosticsEnabled = enabled;
            this.activeBackendId = backendId == null ? "" : backendId;
            this.backendFallbackReason = fallbackReason == null ? "" : fallbackReason;
        }

        private void publish(boolean truncationSignalEnabled, boolean executionProgressEnabled,
                int executedCount) {
            projection.sampleAndPublish(preview, null, phaseProjection, 0L, 0L, 0L, 0L, 0L,
                    truncationSignalEnabled, executionProgressEnabled, executedCount,
                    ChainPreviewBackendDiagnostics.of(
                            backendDiagnosticsEnabled, activeBackendId, backendFallbackReason));
        }
    }
}
