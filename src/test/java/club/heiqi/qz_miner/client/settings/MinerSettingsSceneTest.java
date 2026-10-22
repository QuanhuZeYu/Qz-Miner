package club.heiqi.qz_miner.client.settings;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigTestSupport;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.scene.host.SceneHost;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticRequest;
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticSnapshot;
import club.heiqi.uilib.ui.scene.semantics.SceneSemantics;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;
import club.heiqi.uilib.ui.scene.text.TextLayout;

/** 运行真实业务 page/session/控件；固定字体度量只替代 headless 环境的外部字体后端，不验收字形。 */
public class MinerSettingsSceneTest {
    private File directory;
    private final Properties language = new Properties();
    @Before public void setUp() throws Exception {
        ConfigTestSupport.reset();
        directory = Files.createTempDirectory("qz-settings-scene-").toFile();
        ConfigBootstrap.bootstrap(directory, null);
        language.load(new InputStreamReader(getClass().getResourceAsStream("/assets/qz_miner/lang/en_US.lang"), StandardCharsets.UTF_8));
    }
    @After public void tearDown() { ConfigTestSupport.reset(); delete(directory); }

    @Test public void openingAndRestoringNumericValueDoesNotInventUnsavedChanges() throws Exception {
        try (Fixture fixture = new Fixture(MinerSettingsAccess.MENU, 1100)) {
            Assert.assertFalse(fixture.draft.dirty());
            SceneSemanticSnapshot.Entry radius = fixture.editor(language.getProperty("config.qz_miner.chainRadius.label"));
            fixture.setText(radius, "24");
            Assert.assertTrue(fixture.draft.dirty());
            fixture.setText(fixture.editor(language.getProperty("config.qz_miner.chainRadius.label")),
                    String.valueOf(fixture.draft.baseline().get(QzMinerConfigSchema.CHAIN_RADIUS).intValue()));
            Assert.assertFalse(fixture.draft.dirty());
            fixture.session.escape(() -> Assert.fail("页面应处理自己的离开选择"));
            fixture.session.frame();
            Assert.assertEquals(1, fixture.left.get());
        }
    }

    @Test public void remotePageHasNoServerOrToolEditorAndCanCloseCleanlyAtNarrowWidth() throws Exception {
        try (Fixture fixture = new Fixture(MinerSettingsAccess.REMOTE, 480)) {
            Assert.assertFalse(fixture.draft.dirty());
            Assert.assertFalse(fixture.host.snapshot.entries.stream().anyMatch(entry ->
                    entry.actions.contains(SceneSemantics.Action.SET_TEXT)));
            Assert.assertTrue(fixture.host.snapshot.entries.stream().anyMatch(entry ->
                    "REMOTE_VALUE_UNKNOWN".equals(entry.values.name)));
            fixture.session.escape(() -> Assert.fail("关闭不得绕过页面"));
            fixture.session.frame();
            Assert.assertEquals(1, fixture.left.get());
        }
    }

    @Test public void savingThroughFormalActionCompletesInsideThePageDomain() throws Exception {
        try (Fixture fixture = new Fixture(MinerSettingsAccess.MENU, 1100)) {
            fixture.setText(fixture.editor(language.getProperty("config.qz_miner.chainRadius.label")), "24");
            SceneSemanticSnapshot.Entry save = fixture.host.snapshot.entries.stream()
                    .filter(entry -> language.getProperty("settings.qz_miner.save").equals(entry.values.name)
                            && entry.actions.contains(SceneSemantics.Action.ACTIVATE))
                    .findFirst().orElseThrow(() -> new AssertionError("没有正式保存动作"));
            java.util.concurrent.CompletableFuture<SceneSemanticRequest.Result> saved = fixture.session.semantic(
                    SceneSemanticRequest.of(save.id, SceneSemantics.Action.ACTIVATE));
            fixture.session.frame();
            Assert.assertEquals(SceneSemanticRequest.Result.APPLIED, saved.get(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(24.0, ConfigBootstrap.document().get(QzMinerConfigSchema.CHAIN_RADIUS), 0.0);
            Assert.assertEquals(1, fixture.left.get());
        }
    }

    @Test public void stalePageKeepsItsVisibleFailureAndDraftAfterSaveCompletes() throws Exception {
        try (Fixture fixture = new Fixture(MinerSettingsAccess.MENU, 1100)) {
            fixture.setText(fixture.editor(language.getProperty("config.qz_miner.chainRadius.label")), "24");
            java.util.Map<club.heiqi.uilib.ui.config.ConfigKey<?>, Object> updates = new java.util.LinkedHashMap<>();
            updates.put(QzMinerConfigSchema.GREETING, "another-entry");
            ConfigBootstrap.commit(updates);
            SceneSemanticSnapshot.Entry save = fixture.host.snapshot.entries.stream()
                    .filter(entry -> language.getProperty("settings.qz_miner.save").equals(entry.values.name)
                            && entry.actions.contains(SceneSemantics.Action.ACTIVATE)).findFirst().orElseThrow(AssertionError::new);
            java.util.concurrent.CompletableFuture<SceneSemanticRequest.Result> submitted = fixture.session.semantic(
                    SceneSemanticRequest.of(save.id, SceneSemantics.Action.ACTIVATE));
            fixture.session.frame(); submitted.get(5, java.util.concurrent.TimeUnit.SECONDS); fixture.session.frame();
            Assert.assertEquals(0, fixture.left.get());
            Assert.assertTrue(fixture.draft.dirty());
            Assert.assertTrue(fixture.session.inspect().get().entries.stream().anyMatch(entry ->
                    entry.name != null && entry.name.startsWith(language.getProperty("settings.qz_miner.failed"))));
        }
    }

    @Test public void invalidatedDirtyPageCanStillDiscardThroughTheFormalDialog() throws Exception {
        java.util.Queue<Runnable> queued = new java.util.ArrayDeque<Runnable>();
        try (Fixture fixture = new Fixture(MinerSettingsAccess.MENU, 1100, queued::add)) {
            fixture.setText(fixture.editor(language.getProperty("config.qz_miner.chainRadius.label")), "24");
            byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
            SceneSemanticSnapshot.Entry save = fixture.host.snapshot.entries.stream()
                    .filter(entry -> language.getProperty("settings.qz_miner.save").equals(entry.values.name)
                            && entry.actions.contains(SceneSemantics.Action.ACTIVATE)).findFirst().orElseThrow(AssertionError::new);
            java.util.concurrent.CompletableFuture<SceneSemanticRequest.Result> submitted = fixture.session.semantic(
                    SceneSemanticRequest.of(save.id, SceneSemantics.Action.ACTIVATE));
            fixture.session.frame(); submitted.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertEquals(1, queued.size());
            fixture.lifecycle.set(false);
            fixture.domain.execute(() -> fixture.page.active(false));
            fixture.session.escape(() -> Assert.fail("仍应由页面处理离开")); fixture.session.frame();
            SceneSemanticSnapshot.Entry discard = fixture.host.snapshot.entries.stream()
                    .filter(entry -> language.getProperty("settings.qz_miner.discard").equals(entry.values.name)
                            && entry.actions.contains(SceneSemantics.Action.ACTIVATE)).findFirst().orElseThrow(AssertionError::new);
            Assert.assertTrue(discard.enabled);
            java.util.concurrent.CompletableFuture<SceneSemanticRequest.Result> action = fixture.session.semantic(
                    SceneSemanticRequest.of(discard.id, SceneSemantics.Action.ACTIVATE));
            fixture.session.frame();
            Assert.assertEquals(SceneSemanticRequest.Result.APPLIED, action.get(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(1, fixture.left.get());
            fixture.session.close(); queued.remove().run();
            Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
        }
    }

    @Test public void remoteConfirmationRefreshChangesOnlyReportedFacts() throws Exception {
        try (Fixture fixture = new Fixture(MinerSettingsAccess.REMOTE, 1100)) {
            fixture.reportedFact.set("SERVER_CONFIRMED_RADIUS");
            fixture.domain.execute(fixture.page::refreshServerFacts);
            fixture.session.frame();
            Assert.assertTrue(fixture.host.snapshot.entries.stream().anyMatch(entry ->
                    "SERVER_CONFIRMED_RADIUS".equals(entry.values.name)));
            Assert.assertFalse(fixture.draft.dirty());
        }
    }

    private final class Fixture implements AutoCloseable {
        final ReactiveDomain domain = ReactiveDomain.direct();
        final MinerSettingsDraft draft;
        final Host host;
        final AtomicInteger left = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean lifecycle = new java.util.concurrent.atomic.AtomicBoolean(true);
        final java.util.concurrent.atomic.AtomicReference<String> reportedFact =
                new java.util.concurrent.atomic.AtomicReference<String>("REMOTE_VALUE_UNKNOWN");
        final MinerSettingsPage page;
        final ScenePageSession session;
        Fixture(MinerSettingsAccess access, int width) { this(access, width, Runnable::run); }
        Fixture(MinerSettingsAccess access, int width, java.util.concurrent.Executor io) {
            host = new Host(width);
            draft = new MinerSettingsDraft(ConfigBootstrap.document(), access);
            MinerSettingsPage[] created = new MinerSettingsPage[1];
            domain.execute(() -> created[0] = new MinerSettingsPage(domain, draft,
                    new MinerSettingsSave(io, task -> domain.execute(task), lifecycle::get),
                    key -> language.getProperty(key, key), key -> reportedFact.get(),
                    left::incrementAndGet, left::incrementAndGet, left::incrementAndGet));
            page = created[0];
            session = ScenePageSession.open(domain, host, MEASURER, page);
            session.frame();
            Assert.assertNotNull(host.paint);
            Assert.assertFalse(host.paint.commands().isEmpty());
            java.util.UUID[] templateId = new java.util.UUID[1];
            domain.execute(() -> templateId[0] = session.runtime().roots().get(0).children().get(0).id());
            club.heiqi.uilib.ui.scene.inspection.SceneInspectionSnapshot inspected;
            try { inspected = session.inspect().get(); }
            catch (Exception failure) { throw new AssertionError(failure); }
            club.heiqi.uilib.ui.scene.inspection.SceneInspectionSnapshot.Entry template = inspected.entries.stream()
                    .filter(entry -> entry.nodeId.equals(templateId[0])).findFirst().orElseThrow(AssertionError::new);
            Assert.assertEquals("关闭的 dialog 不得占用页面水平布局空间", width, template.layoutBox.width);
            Assert.assertEquals("关闭的 dialog 不得平分页高", 760, template.layoutBox.height);
        }
        SceneSemanticSnapshot.Entry editor(String label) {
            return host.snapshot.entries.stream().filter(entry -> entry.actions.contains(SceneSemantics.Action.SET_TEXT)
                    && (label.equals(entry.values.name) || entry.labelledBy.stream().anyMatch(id -> {
                        SceneSemanticSnapshot.Entry name = host.snapshot.get(id);
                        return name != null && label.equals(name.values.name);
                    }))).findFirst().orElseThrow(() -> new AssertionError("没有正式文本控件: " + label));
        }
        void setText(SceneSemanticSnapshot.Entry entry, String value) throws Exception {
            java.util.concurrent.CompletableFuture<SceneSemanticRequest.Result> edit =
                    session.semantic(SceneSemanticRequest.text(entry.id, value));
            session.frame();
            Assert.assertEquals(SceneSemanticRequest.Result.APPLIED, edit.get(5, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.CompletableFuture<SceneSemanticRequest.Result> submit =
                    session.semantic(SceneSemanticRequest.of(entry.id, SceneSemantics.Action.SUBMIT));
            session.frame();
            Assert.assertEquals(SceneSemanticRequest.Result.APPLIED, submit.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        public void close() { session.close(); }
    }
    private static final SceneTextMeasurer MEASURER = new SceneTextMeasurer() {
        public TextLayout layout(String text, club.heiqi.uilib.ui.scene.text.TextStyle style, int width) {
            return new TextLayout(Math.min(width, text.length() * 8), 18, style,
                    Arrays.asList(new TextLayout.Line(text, 0, 14)));
        }
        @Override public TextLayout layout(String text, club.heiqi.uilib.ui.scene.text.TextStyle style, int width,
                club.heiqi.uilib.ui.scene.text.TextPolicy policy) { return layout(text, style, width).withPolicy(policy); }
    };
    private static final class Host implements SceneHost {
        final int width;
        PaintPlan paint;
        SceneSemanticSnapshot snapshot = SceneSemanticSnapshot.EMPTY;
        Host(int width) { this.width = width; }
        public int width() { return width; }
        public int height() { return 760; }
        public long metricsRevision() { return 1L; }
        public void semanticsChanged(SceneSemanticSnapshot snapshot) { this.snapshot = snapshot; }
        public void replay(PaintPlan paint) { this.paint = paint; }
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
