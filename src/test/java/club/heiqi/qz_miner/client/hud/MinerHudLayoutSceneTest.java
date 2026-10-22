package club.heiqi.qz_miner.client.hud;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import club.heiqi.qz_miner.client.settings.MinerSettingsSave;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigTestSupport;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.scene.host.SceneHost;
import club.heiqi.uilib.ui.scene.host.ScenePageSession;
import club.heiqi.uilib.ui.scene.input.ScenePointerEvent;
import club.heiqi.uilib.ui.scene.paint.PaintPlan;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;
import club.heiqi.uilib.ui.scene.text.TextLayout;
import club.heiqi.uilib.ui.scene.text.TextPolicy;
import club.heiqi.uilib.ui.scene.text.TextStyle;

/** 业务布局在真实 Scene 中可被指针命中；拖放只改草稿，放弃不触盘。 */
public class MinerHudLayoutSceneTest {
    private File directory;
    @Before public void setUp() throws Exception {
        ConfigTestSupport.reset(); directory = Files.createTempDirectory("qz-hud-layout-scene-").toFile();
        ConfigBootstrap.bootstrap(directory, null);
    }
    @After public void tearDown() { ConfigTestSupport.reset(); delete(directory); }
    @Test public void defaultHudCanDragWithoutToolbarInterceptionAndCancelKeepsDisk() throws Exception {
        for (int width : new int[] {1100, 480}) {
            byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
            ReactiveDomain domain = ReactiveDomain.direct();
            MinerHudLayoutPage[] page = new MinerHudLayoutPage[1];
            AtomicInteger returned = new AtomicInteger();
            domain.execute(() -> page[0] = new MinerHudLayoutPage(domain, ConfigBootstrap.document(),
                    new MinerSettingsSave(Runnable::run, task -> domain.execute(task), () -> true),
                    domain.signal(1f), () -> true, key -> key.equals("settings.qz_miner.hud.preview_title") ? "Miner HUD" : "Tool",
                    returned::incrementAndGet));
            SceneHost host = new SceneHost() {
                public int width() { return width; }
                public int height() { return 760; }
                public long metricsRevision() { return 1L; }
                public void replay(PaintPlan paint) { }
            };
            try (ScenePageSession session = ScenePageSession.open(domain, host, MEASURER, page[0])) {
                session.frame();
                session.pointer(ScenePointerEvent.down(0, 24, 24)); session.frame();
                session.pointer(ScenePointerEvent.move(104, 88)); session.frame();
                session.pointer(ScenePointerEvent.up(0, 104, 88)); session.frame();
                domain.execute(() -> Assert.assertNotEquals("默认左上 HUD 必须能拖动", page[0].initial, page[0].placement.get()));
                Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
                domain.execute(page[0]::back);
                Assert.assertEquals(1, returned.get());
            }
            Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
        }
    }
    @Test public void everyAnchorAndScaleKeepsTheSameViewportAsNormalHud() throws Exception {
        ReactiveDomain domain = ReactiveDomain.direct();
        MinerHudLayoutPage[] page = new MinerHudLayoutPage[1];
        club.heiqi.uilib.ui.reactive.Signal<Float>[] guiScale = new club.heiqi.uilib.ui.reactive.Signal[1];
        domain.execute(() -> {
            guiScale[0] = domain.signal(1f);
            page[0] = new MinerHudLayoutPage(domain, ConfigBootstrap.document(),
                    new MinerSettingsSave(Runnable::run, task -> domain.execute(task), () -> true),
                    guiScale[0], () -> true, key -> key.equals("settings.qz_miner.hud.preview_title") ? "HUD preview" : "Tool", () -> { });
            page[0].toolsOpen.set(false);
        });
        SceneHost host = new SceneHost() {
            public int width() { return 1100; }
            public int height() { return 760; }
            public long metricsRevision() { return 1L; }
            public void replay(PaintPlan paint) { }
        };
        try (ScenePageSession editor = ScenePageSession.open(domain, host, MEASURER, context -> {
            context.motionPolicy(() -> club.heiqi.uilib.ui.scene.motion.MotionPolicy.OFF);
            page[0].build(context);
        }); ScenePageSession normal = ScenePageSession.open(domain, host, MEASURER, context -> {
            context.motionPolicy(() -> club.heiqi.uilib.ui.scene.motion.MotionPolicy.OFF);
            context.mountOwned(club.heiqi.uilib.ui.scene.hud.SceneHud.create(context,
                    new MinerHudModel(domain, key -> key.equals("settings.qz_miner.hud.preview_title") ? "HUD preview" : "Tool").content(context),
                    page[0].placement, guiScale[0]));
        })) {
            for (club.heiqi.uilib.ui.scene.hud.SceneHudPlacement.Anchor anchor : club.heiqi.uilib.ui.scene.hud.SceneHudPlacement.Anchor.values()) {
                for (float scale : new float[] {1f, 2f}) {
                    domain.execute(() -> domain.batch(() -> {
                        page[0].anchor.set(anchor);
                        page[0].placement.set(new club.heiqi.uilib.ui.scene.hud.SceneHudPlacement(anchor, 12, 12, 1));
                        guiScale[0].set(scale);
                    }));
                    editor.frame(); normal.frame();
                    club.heiqi.uilib.ui.scene.layout.LayoutBox expected = title(normal);
                    club.heiqi.uilib.ui.scene.layout.LayoutBox actual = title(editor);
                    Assert.assertEquals(anchor + " guiScale=" + scale + " x", expected.x, actual.x);
                    Assert.assertEquals(anchor + " guiScale=" + scale + " y", expected.y, actual.y);
                    Assert.assertEquals(expected.width, actual.width);
                    Assert.assertEquals(expected.height, actual.height);
                }
            }
        }
    }
    private static club.heiqi.uilib.ui.scene.layout.LayoutBox title(ScenePageSession session) throws Exception {
        return session.inspect().get().entries.stream().filter(entry -> "HUD preview".equals(entry.name))
                .findFirst().orElseThrow(() -> new AssertionError("未命中真实 HUD 标题几何")).worldBounds;
    }

    @Test public void hudRowsFollowFactsWithMotionOffAndReduced() throws Exception {
        for (club.heiqi.uilib.ui.scene.motion.MotionPolicy policy : new club.heiqi.uilib.ui.scene.motion.MotionPolicy[] {
                club.heiqi.uilib.ui.scene.motion.MotionPolicy.OFF, club.heiqi.uilib.ui.scene.motion.MotionPolicy.REDUCED}) {
            ReactiveDomain domain = ReactiveDomain.direct();
            MinerHudModel[] model = new MinerHudModel[1];
            club.heiqi.qz_miner.chain.state.ChainClientState state = new club.heiqi.qz_miner.chain.state.ChainClientState();
            domain.execute(() -> { model[0] = new MinerHudModel(domain, key -> key); model[0].context(state, true, "en_US"); });
            long[] clock = {1_000_000L};
            SceneHost host = new SceneHost() {
                public int width() { return 1100; }
                public int height() { return 760; }
                public long metricsRevision() { return 1L; }
                public long frameNanos() { return clock[0]; }
                public void replay(PaintPlan paint) { }
            };
            try (ScenePageSession session = ScenePageSession.open(domain, host, MEASURER, context -> {
                context.motionPolicy(() -> policy); context.mountOwned(model[0].content(context));
            })) {
                session.frame();
                domain.execute(() -> { state.setChainKeyPressed(true); model[0].context(state, true, "en_US"); });
                session.frame(); clock[0] += 1_000_000_000L; session.frame();
                Assert.assertTrue(policy.toString(), session.inspect().get().entries.stream().anyMatch(entry ->
                        entry.visible && "hud.qz_miner.short.radius".equals(entry.name)));
                domain.execute(() -> { state.setChainKeyPressed(false); model[0].context(state, true, "en_US"); });
                session.frame(); clock[0] += 1_000_000_000L; session.frame();
                Assert.assertFalse(policy.toString(), session.inspect().get().entries.stream().anyMatch(entry ->
                        entry.visible && "hud.qz_miner.short.radius".equals(entry.name)));
            }
        }
    }

    @Test public void hudSurfacePaintFollowsLightDarkLightThemeWithoutFadingText() throws Exception {
        ReactiveDomain domain = ReactiveDomain.direct();
        club.heiqi.uilib.ui.config.ConfigSession[] config = new club.heiqi.uilib.ui.config.ConfigSession[1];
        club.heiqi.uilib.ui.scene.control.ThemeConfiguration[] themes = new club.heiqi.uilib.ui.scene.control.ThemeConfiguration[1];
        club.heiqi.uilib.ui.reactive.Owner[] owner = new club.heiqi.uilib.ui.reactive.Owner[1];
        domain.execute(() -> {
            owner[0] = domain.owner();
            config[0] = new club.heiqi.uilib.ui.config.ConfigSession(domain, club.heiqi.uilib.ui.config.ConfigSchema.of(
                    club.heiqi.uilib.ui.scene.control.ThemeConfiguration.KEY));
            themes[0] = new club.heiqi.uilib.ui.scene.control.ThemeConfiguration(domain, config[0], owner[0]);
        });
        java.util.concurrent.atomic.AtomicReference<PaintPlan> rendered = new java.util.concurrent.atomic.AtomicReference<PaintPlan>();
        SceneHost host = new SceneHost() {
            public int width() { return 1100; }
            public int height() { return 760; }
            public long metricsRevision() { return 1L; }
            public club.heiqi.uilib.ui.scene.control.ThemeConfiguration themeConfiguration() { return themes[0]; }
            public void replay(PaintPlan paint) { rendered.set(paint); }
        };
        try (ScenePageSession session = ScenePageSession.open(domain, host, MEASURER, context -> {
            context.motionPolicy(() -> club.heiqi.uilib.ui.scene.motion.MotionPolicy.OFF);
            context.mountOwned(new MinerHudModel(domain, key -> "HUD").content(context));
        })) {
            for (club.heiqi.uilib.ui.scene.control.ThemeMode mode : new club.heiqi.uilib.ui.scene.control.ThemeMode[] {
                    club.heiqi.uilib.ui.scene.control.ThemeMode.LIGHT, club.heiqi.uilib.ui.scene.control.ThemeMode.DARK,
                    club.heiqi.uilib.ui.scene.control.ThemeMode.LIGHT}) {
                domain.execute(() -> config[0].set(club.heiqi.uilib.ui.scene.control.ThemeConfiguration.KEY, mode));
                session.frame();
                int rgb = new club.heiqi.uilib.ui.scene.control.SceneAppearance.Palette(mode.sceneTheme()).background & 0xFFFFFF;
                Assert.assertTrue(mode + " expected RGB=" + Integer.toHexString(rgb) + " actual="
                        + rendered.get().commands().stream().map(command -> command.kind + ":" + Integer.toHexString(command.color))
                                .distinct().collect(java.util.stream.Collectors.joining(",")), rendered.get().commands().stream().anyMatch(command ->
                        command.kind == club.heiqi.uilib.ui.scene.paint.PaintCommand.Kind.RECT
                                && (command.color & 0xFFFFFF) == rgb && (command.color >>> 24) > 0 && (command.color >>> 24) < 255));
                Assert.assertTrue("文字保持不透明", rendered.get().commands().stream().anyMatch(command ->
                        command.kind == club.heiqi.uilib.ui.scene.paint.PaintCommand.Kind.TEXT && (command.color >>> 24) == 255));
            }
        } finally { domain.execute(owner[0]::close); }
    }

    @Test public void decimalScaleOpensCleanMovementPreservesAuthorityAndExplicitScaleSaves() throws Exception {
        for (double scale : new double[] {1.1, 1.2}) {
            java.util.Map<club.heiqi.uilib.ui.config.ConfigKey<?>, Object> initialValues = new java.util.LinkedHashMap<>();
            initialValues.put(club.heiqi.qz_miner.config.QzMinerConfigSchema.HUD_SCALE, scale);
            initialValues.put(club.heiqi.qz_miner.config.QzMinerConfigSchema.HUD_OFFSET_X, 1.1);
            initialValues.put(club.heiqi.qz_miner.config.QzMinerConfigSchema.HUD_OFFSET_Y, 2.2);
            ConfigBootstrap.commit(initialValues);
            ReactiveDomain domain = ReactiveDomain.direct();
            MinerHudLayoutPage[] page = new MinerHudLayoutPage[1];
            domain.execute(() -> {
                page[0] = new MinerHudLayoutPage(domain, ConfigBootstrap.document(),
                        new MinerSettingsSave(Runnable::run, task -> domain.execute(task), () -> true),
                        domain.signal(1f), () -> true, key -> key, () -> { });
                Assert.assertFalse("打开合法小数布局不应变脏", page[0].dirty());
                club.heiqi.uilib.ui.scene.hud.SceneHudPlacement p = page[0].initial;
                page[0].placement.set(new club.heiqi.uilib.ui.scene.hud.SceneHudPlacement(p.anchor, 8.1f, p.offsetY, p.scale));
                for (java.util.Map.Entry<club.heiqi.uilib.ui.config.ConfigKey<?>, Object> change :
                        MinerHudPlacement.updates(page[0].draft.baseline(), page[0].placement.get()).entrySet())
                    page[0].draft.set(change.getKey(), change.getValue());
                Assert.assertTrue(page[0].draft.dirty());
                page[0].placement.set(p);
                for (java.util.Map.Entry<club.heiqi.uilib.ui.config.ConfigKey<?>, Object> change :
                        MinerHudPlacement.updates(page[0].draft.baseline(), p).entrySet()) page[0].draft.set(change.getKey(), change.getValue());
                Assert.assertFalse("恢复初始展示投影撤掉对应编辑", page[0].draft.dirty());
                page[0].placement.set(new club.heiqi.uilib.ui.scene.hud.SceneHudPlacement(p.anchor, 8.1f, p.offsetY, p.scale));
                page[0].save();
            });
            Assert.assertEquals(Double.valueOf(scale), ConfigBootstrap.document().get(club.heiqi.qz_miner.config.QzMinerConfigSchema.HUD_SCALE));
            Assert.assertEquals(Double.valueOf(2.2), ConfigBootstrap.document().get(club.heiqi.qz_miner.config.QzMinerConfigSchema.HUD_OFFSET_Y));
            Assert.assertEquals(Double.valueOf(8.1), ConfigBootstrap.document().get(club.heiqi.qz_miner.config.QzMinerConfigSchema.HUD_OFFSET_X));
            domain.execute(() -> {
                MinerHudLayoutPage scaled = new MinerHudLayoutPage(domain, ConfigBootstrap.document(),
                        new MinerSettingsSave(Runnable::run, task -> domain.execute(task), () -> true),
                        domain.signal(1f), () -> true, key -> key, () -> { });
                scaled.scale.set("1.7"); Assert.assertTrue(scaled.dirty()); scaled.save();
            });
            Assert.assertEquals(Double.valueOf(1.7), ConfigBootstrap.document().get(club.heiqi.qz_miner.config.QzMinerConfigSchema.HUD_SCALE));
        }
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
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
