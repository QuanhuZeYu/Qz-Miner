package club.heiqi.qz_miner.client.hud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.scene.control.SceneControl;
import club.heiqi.uilib.ui.scene.layout.Constraints;
import club.heiqi.uilib.ui.scene.layout.ChildPlacements;
import club.heiqi.uilib.ui.scene.layout.LayoutBox;
import club.heiqi.uilib.ui.scene.layout.MeasuredSize;
import club.heiqi.uilib.ui.scene.layout.SceneChildMeasurer;
import club.heiqi.uilib.ui.scene.layout.SceneGeometryView;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.paint.PaintPlanBuilder;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;

/** 仪表布局消费本批真实文字测量；方位关系不依赖语言长度、分辨率或预先写死的文字宽度。 */
final class MinerHudDialLayout implements SceneControl {
    private final ReadableSignal<Float> em;
    private final ReadableSignal<Boolean> navigation;
    private final SceneNode ring, center, north, south, west, east;
    MinerHudDialLayout(ReadableSignal<Float> em, ReadableSignal<Boolean> navigation,
            SceneNode ring, SceneNode center, SceneNode north, SceneNode south, SceneNode west, SceneNode east) {
        this.em = em; this.navigation = navigation; this.ring = ring; this.center = center;
        this.north = north; this.south = south; this.west = west; this.east = east;
    }
    @Override public List<ReadableSignal<?>> measureDependencies(SceneNode node) { return Arrays.asList(em, navigation); }
    @Override public MeasuredSize measure(SceneNode node, Constraints limits, SceneTextMeasurer text) { return new MeasuredSize(0, 0); }
    @Override public MeasuredSize measure(SceneNode node, Constraints limits, SceneTextMeasurer text, SceneChildMeasurer children) {
        return layout(limits, children, false).contentSize();
    }
    @Override public ChildPlacements arrange(SceneNode node, LayoutBox box, SceneChildMeasurer children) {
        return layout(new Constraints(box.width, box.height), children, true);
    }
    private ChildPlacements layout(Constraints limits, SceneChildMeasurer children, boolean allocated) {
        int gap = Math.max(2, Math.round(em.get() * .6f));
        // 自然排版以字体尺度限行；最终空间由 SceneHud 等比适配，不在 measure/arrange 间换一套宽度。
        MeasuredSize current = measure(children, center, new Constraints(Math.round(em.get() * 16), Integer.MAX_VALUE));
        List<ChildPlacements.Entry> placed = new ArrayList<>();
        if (!navigation.get()) {
            int width = allocated ? limits.availableWidth : current.width + gap * 2;
            int height = allocated ? limits.availableHeight : current.height + gap * 2;
            place(placed, center, (width - current.width) / 2, gap, current);
            return new ChildPlacements(placed, new MeasuredSize(width, height));
        }
        int sideLimit = Math.max(0, Math.round(em.get() * 8));
        Constraints sideConstraints = new Constraints(sideLimit, Integer.MAX_VALUE);
        MeasuredSize left = measure(children, west, sideConstraints), right = measure(children, east, sideConstraints);
        int side = Math.max(left.width, right.width);
        int diameter = Math.max(Math.round(em.get() * 10.6f), Math.max(current.width + gap * 4, current.height + gap * 6));
        Constraints vertical = new Constraints(diameter, Integer.MAX_VALUE);
        MeasuredSize above = measure(children, north, vertical), below = measure(children, south, vertical);
        int width = allocated ? limits.availableWidth
                : Math.max(diameter + side * 2 + gap * 2, Math.max(above.width, below.width));
        int middle = Math.max(diameter, Math.max(left.height, right.height));
        int height = above.height + below.height + middle + gap * 2;
        int cx = width / 2, cy = above.height + gap + middle / 2;
        placed.add(new ChildPlacements.Entry(ring, new LayoutBox(cx - diameter / 2, cy - diameter / 2, diameter, diameter)));
        place(placed, center, cx - current.width / 2, cy - current.height / 2, current);
        place(placed, north, cx - above.width / 2, 0, above);
        place(placed, south, cx - below.width / 2, above.height + middle + gap * 2, below);
        place(placed, west, cx - diameter / 2 - gap - left.width, cy - left.height / 2, left);
        place(placed, east, cx + diameter / 2 + gap, cy - right.height / 2, right);
        return new ChildPlacements(placed, new MeasuredSize(width, height));
    }
    private static MeasuredSize measure(SceneChildMeasurer children, SceneNode node, Constraints limits) {
        return children.flowChildren().contains(node) ? children.measureChild(node, limits) : new MeasuredSize(0, 0);
    }
    private static void place(List<ChildPlacements.Entry> placed, SceneNode node, int x, int y, MeasuredSize size) {
        if (!node.visible()) return;
        placed.add(new ChildPlacements.Entry(node, new LayoutBox(x, y, size.width, size.height)));
    }
    @Override public void paint(SceneNode node, SceneGeometryView geometry, PaintPlanBuilder target) { }
}
