package club.heiqi.qz_miner.client.settings;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigTestSupport;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.scene.host.SceneHost;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticRequest;
import club.heiqi.uilib.ui.scene.semantics.SceneSemanticSnapshot;
import club.heiqi.uilib.ui.scene.semantics.SceneSemantics;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.TextPolicy;
import club.heiqi.uilib.ui.scene.text.TextStyle;

/** 正式对象组编辑器挂载即执行真实 Effect，不用纯序列化函数代替开页与控件交互。 */
public class MinerObjectGroupsSceneTest {
    private File directory;
    @Before public void setUp() throws Exception {
        ConfigTestSupport.reset(); directory = Files.createTempDirectory("qz-group-mode-order-").toFile();
        ConfigBootstrap.bootstrap(directory, null);
    }
    @After public void tearDown() { ConfigTestSupport.reset(); delete(directory); }
    @Test public void openingAndEditingKeepsOriginalModeOrderWhileFormalSwitchesStillUpdate() throws Exception {
        List<String> original = Arrays.asList(ObjectGroupMode.CHAIN_ORE, ObjectGroupMode.CHAIN_BASE);
        Map<String, Object> group = new LinkedHashMap<String, Object>();
        group.put("id", "ore-group"); group.put("modes", original);
        group.put("members", Collections.singletonList("unknown_mod:ore@*"));
        ConfigBootstrap.commit(Collections.singletonMap(QzMinerConfigSchema.OBJECT_GROUPS, Collections.singletonList(group)));
        MinerSettingsDraft draft = new MinerSettingsDraft(ConfigBootstrap.document(), MinerSettingsAccess.MENU);
        ReactiveDomain domain = ReactiveDomain.direct();
        Host host = new Host();
        try (ScenePageSession session = ScenePageSession.open(domain, host, MEASURER, context -> {
            context.motionPolicy(() -> club.heiqi.uilib.ui.scene.motion.MotionPolicy.OFF);
            context.mountOwned(new MinerObjectGroupsEditor(context, draft, () -> { },
                    () -> draft.validate().errors, () -> true, key -> () -> key).create());
        })) {
            session.frame();
            Assert.assertFalse("合法非默认模式顺序开页不可误报编辑", draft.dirty());
            Assert.assertEquals(original, modes(draft));
            SceneSemanticSnapshot.Entry id = host.snapshot.entries.stream().filter(entry ->
                    entry.actions.contains(SceneSemantics.Action.SET_TEXT) && "ore-group".equals(entry.values.value))
                    .findFirst().orElseThrow(() -> new AssertionError("没有正式组 ID 编辑器"));
            apply(session, SceneSemanticRequest.text(id.id, "renamed-group"));
            apply(session, SceneSemanticRequest.of(id.id, SceneSemantics.Action.SUBMIT));
            Assert.assertTrue(draft.dirty());
            Assert.assertEquals("编辑 ID 不暗改模式列表", original, modes(draft));
            toggle(session, host, ObjectGroupMode.CHAIN_ORE);
            Assert.assertEquals(Collections.singletonList(ObjectGroupMode.CHAIN_BASE), modes(draft));
            toggle(session, host, ObjectGroupMode.CHAIN_ORE);
            Assert.assertEquals(original, modes(draft));
            toggle(session, host, ObjectGroupMode.INTERACT_CROP);
            toggle(session, host, ObjectGroupMode.CHAIN_LOGGING);
            Assert.assertEquals("新启用模式按稳定展示顺序追加", Arrays.asList(ObjectGroupMode.CHAIN_ORE,
                    ObjectGroupMode.CHAIN_BASE, ObjectGroupMode.CHAIN_LOGGING, ObjectGroupMode.INTERACT_CROP), modes(draft));
            Assert.assertTrue(draft.validate().errors.toString(), draft.validate().valid());
        }
    }
    private static void toggle(ScenePageSession session, Host host, String mode) throws Exception {
        String label = "config.qz_miner.object_group.mode." + mode;
        SceneSemanticSnapshot.Entry control = host.snapshot.entries.stream().filter(entry ->
                entry.actions.contains(SceneSemantics.Action.ACTIVATE) && (label.equals(entry.values.name)
                        || entry.labelledBy.stream().anyMatch(id -> host.snapshot.get(id) != null
                                && label.equals(host.snapshot.get(id).values.name))))
                .findFirst().orElseThrow(() -> new AssertionError("没有正式模式开关: " + mode));
        apply(session, SceneSemanticRequest.of(control.id, SceneSemantics.Action.ACTIVATE));
    }
    private static void apply(ScenePageSession session, SceneSemanticRequest request) throws Exception {
        java.util.concurrent.CompletableFuture<SceneSemanticRequest.Result> result = session.semantic(request);
        session.frame(); Assert.assertEquals(SceneSemanticRequest.Result.APPLIED,
                result.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }
    @SuppressWarnings("unchecked") private static List<String> modes(MinerSettingsDraft draft) {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) draft.value(QzMinerConfigSchema.OBJECT_GROUPS);
        return (List<String>) groups.get(0).get("modes");
    }
    private static final SceneTextMeasurer MEASURER = new SceneTextMeasurer() {
        public TextLayout layout(String text, TextStyle style, int width) {
            return new TextLayout(Math.min(width, text.length() * 8), 18, style,
                    Arrays.asList(new TextLayout.Line(text, 0, 14)));
        }
        public TextLayout layout(String text, TextStyle style, int width, TextPolicy policy) {
            return layout(text, style, width).withPolicy(policy);
        }
    };
    private static final class Host implements SceneHost {
        SceneSemanticSnapshot snapshot = SceneSemanticSnapshot.EMPTY;
        public int width() { return 1100; }
        public int height() { return 1800; }
        public long metricsRevision() { return 1L; }
        public void semanticsChanged(SceneSemanticSnapshot snapshot) { this.snapshot = snapshot; }
        public void replay(PaintPlan paint) { }
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete();
    }
}
