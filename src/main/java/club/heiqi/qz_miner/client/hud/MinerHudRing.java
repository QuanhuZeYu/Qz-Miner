package club.heiqi.qz_miner.client.hud;

import java.util.Collections;
import java.util.List;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.control.SceneControl;
import club.heiqi.uilib.ui.scene.geometry.ScenePath;
import club.heiqi.uilib.ui.scene.geometry.SceneStroke;
import club.heiqi.uilib.ui.scene.geometry.SceneMesh;
import club.heiqi.uilib.ui.scene.geometry.SceneClipRegion;
import club.heiqi.uilib.ui.scene.geometry.SceneTransform;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.Constraints;
import club.heiqi.uilib.ui.scene.layout.ChildPlacements;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.MeasuredSize;
import club.heiqi.uilib.ui.scene.layout.SceneChildMeasurer;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintCommand;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;

/** 刻度只表达导航，不借装饰伪造任务进度；路径在尺寸/像素精度变化时准备，转动只提交矩阵。 */
final class MinerHudRing implements SceneControl {
    private static final ScenePath TRACK = ScenePath.ellipse(120, 120, 104, 104);
    private static final ScenePath[] TICKS = {ticks(0), ticks(1), ticks(2), ticks(3)};
    private static final SceneStroke[] TICK_STROKES = {
        new SceneStroke(1.5), new SceneStroke(1.8), new SceneStroke(2), new SceneStroke(2.8)
    };
    private static final ScenePath[] SWEEP = sweep(), TICK_PAIRS = tickPairs();
    private static final SceneStroke STROKE = new SceneStroke(1);
    private final ReadableSignal<SceneAppearance.Palette> colors;
    private final List<ReadableSignal<?>> dependencies;
    private final MinerHudBezel bezel = new MinerHudBezel();
    private final SceneMesh[] ticks = new SceneMesh[TICKS.length];
    private SceneMesh track;
    private final SceneMesh[] sweep = new SceneMesh[SWEEP.length], tickPairs = new SceneMesh[TICK_PAIRS.length];
    private double error = -1;
    private float angle, reveal;
    private MinerHudRing(ReadableSignal<SceneAppearance.Palette> colors) {
        this.colors = colors; dependencies = Collections.singletonList(colors);
    }
    static SceneNode create(ScenePageContext page, MinerHudModel model, ReadableSignal<SceneAppearance.Palette> colors,
            ReadableSignal<Boolean> navigation) {
        MinerHudRing ring = new MinerHudRing(colors);
        SceneNode node = new SceneNode().control(ring).size(Size.fill(1), Size.fill(1));
        node.bind(SceneMotion.background(() -> MinerHudPalette.mode(model.selection.get().mode, colors.get()),
                () -> colors.get().source.feedbackMotion));
        node.bind((runtime, self, owner) -> NodeBinding.motion(model.bearing, () -> colors.get().source.spatialMotion,
                true, "minerRing.rotation", (value, angle) -> { ring.angle = angle; runtime.invalidatePaint(self); })
                .activate(runtime, self, owner));
        // 导航从紧凑态重新展开也沿当前值续接；不要求销毁整个 HUD 才能再次播放。
        node.bind((runtime, self, owner) -> NodeBinding.motionFrom(() -> 0f, () -> navigation.get() ? 1f : 0f,
                () -> MinerHudEntrance.RING, true, "minerRing.reveal", (value, progress) -> {
                    ring.reveal = progress; runtime.invalidatePaint(self);
                }).activate(runtime, self, owner));
        return node;
    }
    private static ScenePath[] sweep() {
        ScenePath[] paths = new ScenePath[12];
        for (int i = 0; i < paths.length; i++) {
            ScenePath.Builder path = ScenePath.builder();
            double start = -Math.PI / 2 + i * Math.PI / paths.length;
            for (int opposite = 0; opposite < 2; opposite++) {
                double angle = start + opposite * Math.PI;
                path.moveTo(120 + Math.cos(angle) * 104, 120 + Math.sin(angle) * 104)
                        .arc(120, 120, 104, angle, Math.PI / paths.length);
            }
            paths[i] = path.build();
        }
        return paths;
    }
    private static ScenePath[] tickPairs() {
        ScenePath[] paths = new ScenePath[24];
        for (int i = 0; i < paths.length; i++) {
            ScenePath.Builder path = ScenePath.builder();
            for (int opposite = 0; opposite < 2; opposite++) tick(path, i + opposite * paths.length);
            paths[i] = path.build();
        }
        return paths;
    }
    private static ScenePath ticks(int level) {
        ScenePath.Builder path = ScenePath.builder();
        for (int i = 0; i < 48; i++) {
            if (level(i) == level) tick(path, i);
        }
        return path.build();
    }
    private static int level(int index) {
        return index % 12 == 0 ? 3 : index % 4 == 0 ? 2 : index % 2 == 0 ? 1 : 0;
    }
    private static void tick(ScenePath.Builder path, int index) {
        int level = level(index);
        double angle = index * Math.PI / 24, start = level == 3 ? 103.5 : level == 2 ? 105 : level == 1 ? 108.5 : 111;
        path.moveTo(120 + Math.sin(angle) * start, 120 - Math.cos(angle) * start)
                .lineTo(120 + Math.sin(angle) * 114.5, 120 - Math.cos(angle) * 114.5);
    }
    @Override public List<ReadableSignal<?>> paintDependencies(SceneNode node) { return dependencies; }
    @Override public MeasuredSize measure(SceneNode node, Constraints limits, SceneTextMeasurer text) { return new MeasuredSize(0, 0); }
    @Override public ChildPlacements arrange(SceneNode node, LayoutBox box, SceneChildMeasurer children) { return new ChildPlacements(Collections.emptyList()); }
    @Override public void paint(SceneNode node, SceneGeometryView geometry, PaintPlanBuilder target) {
        SceneGeometryView.Entry entry = geometry.get(node);
        if (entry == null || entry.box.width == 0 || entry.box.height == 0) return;
        SceneTransform fit = SceneTransform.translation(entry.box.x, entry.box.y)
                .multiply(SceneTransform.scale(entry.box.width / 240.0, entry.box.height / 240.0));
        double scale = geometry.coordinateTransform().maximumScale() * fit.maximumScale();
        if (scale == 0) return;
        prepare(.25 / scale);
        SceneAppearance.Palette palette = colors.get();
        SceneClipRegion clip = SceneClipRegion.rectangle(entry.clip);
        if (reveal < 1) {
            paintEntrance(node, clip, target, fit, palette);
            return;
        }
        bezel.paint(target, clip, fit, palette, node.background(), 1);
        target.add(PaintCommand.path(track, null, MinerHudPalette.track(palette)).withGeometry(fit, clip));
        SceneTransform turn = fit.multiply(SceneTransform.around(120, 120, SceneTransform.rotationDegrees(angle)));
        for (int i = 0; i < ticks.length; i++) target.add(PaintCommand.path(ticks[i], null,
                MinerHudPalette.tick(palette, node.background(), i)).withGeometry(turn, clip));
    }
    private void paintEntrance(SceneNode node, SceneClipRegion clip, PaintPlanBuilder target,
            SceneTransform fit, SceneAppearance.Palette palette) {
        // 用最终尺寸准备的有限网格表达扫入；动画中不重建路径，落定后按刻度等级合并绘制。
        double remaining = 1 - reveal, radius = 1 - .08 * remaining * remaining * remaining;
        SceneTransform grow = fit.multiply(SceneTransform.around(120, 120, SceneTransform.scale(radius, radius)));
        bezel.paint(target, clip, grow, palette, node.background(), phase(reveal, 0, .12f));
        for (int i = 0; i < sweep.length; i++) {
            float opacity = phase(reveal, i * .62f / sweep.length, .28f);
            if (opacity > 0) target.add(PaintCommand.path(sweep[i], null,
                    SceneMotion.alpha(MinerHudPalette.track(palette), opacity)).withGeometry(grow, clip));
        }
        SceneTransform turn = grow.multiply(SceneTransform.around(120, 120,
                SceneTransform.rotationDegrees(angle - 9 * remaining * remaining)));
        for (int i = 0; i < tickPairs.length; i++) {
            float opacity = phase(reveal, i * .68f / tickPairs.length, .24f);
            float glint = (float) (Math.max(0, 1 - Math.abs(reveal - (i + .5) / tickPairs.length) / .09) * remaining);
            int color = MinerHudPalette.highlight(MinerHudPalette.tick(palette, node.background(), level(i)), palette, glint);
            if (opacity > 0) target.add(PaintCommand.path(tickPairs[i], null,
                    SceneMotion.alpha(color, opacity)).withGeometry(turn, clip));
        }
    }
    private static float phase(float progress, float start, float duration) {
        float value = Math.max(0, Math.min(1, (progress - start) / duration));
        return value * value * (3 - 2 * value);
    }
    private void prepare(double nextError) {
        if (error == nextError) return;
        bezel.prepare(nextError); track = TRACK.strokeMesh(STROKE, nextError);
        for (int i = 0; i < ticks.length; i++) ticks[i] = TICKS[i].strokeMesh(TICK_STROKES[i], nextError);
        for (int i = 0; i < sweep.length; i++) sweep[i] = SWEEP[i].strokeMesh(STROKE, nextError);
        for (int i = 0; i < tickPairs.length; i++) tickPairs[i] = TICK_PAIRS[i].strokeMesh(TICK_STROKES[level(i)], nextError);
        error = nextError;
    }
}
