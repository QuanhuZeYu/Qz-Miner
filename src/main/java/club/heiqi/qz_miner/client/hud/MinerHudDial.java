package club.heiqi.qz_miner.client.hud;

import java.util.function.Function;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.scene.control.SceneAnimatedContent;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.motion.EntranceOptions;
import club.heiqi.uilib.ui.scene.motion.PresenceOptions;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;

/** 精密环形的业务组合；模式横向、子模式纵向，过渡和资源释放统一由 Core 承担。 */
final class MinerHudDial {
    private final MinerHudAppearance ui;
    private final ScenePageContext page;
    private final MinerHudModel model;
    private final ReadableSignal<Boolean> navigation;
    MinerHudDial(MinerHudAppearance ui, ScenePageContext page, MinerHudModel model, ReadableSignal<Boolean> navigation) {
        this.ui = ui; this.page = page; this.model = model; this.navigation = navigation;
    }
    SceneNode create() {
        SceneNode center = SceneLayouts.centeredColumn(() -> Math.round(ui.colors.get().textStyle.logicalEm * .12f));
        center.add(animated(value -> value.title, value -> value.mode, 1.16f, false));
        center.add(animated(value -> value.subtitle, value -> value.subMode == null ? value.mode : value.subMode, .82f, true));
        SceneNode marker = new SceneNode().bind(NodeBinding.value(ui.colors, (node, palette) -> node
                .size(Size.fixed(Math.round(palette.textStyle.logicalEm * 1.8f)), Size.fixed(2))))
                .bind(SceneMotion.background(() -> MinerHudPalette.mode(model.selection.get().mode, ui.colors.get()),
                        () -> page.theme().get().feedbackMotion));
        center.add(marker);
        center = ui.panel(center, .7f, () -> ui.colors.get().background).bind(MinerHudEntrance.enter(page, MinerHudEntrance.CENTER,
                () -> 0f, () -> ui.colors.get().textStyle.logicalEm * .3f));
        SceneNode ring = part(MinerHudRing.create(page, model, ui.colors, navigation), 0, 0);
        SceneNode north = neighbor(value -> value.above, 0, -1, true);
        SceneNode south = neighbor(value -> value.below, 0, 1, true);
        SceneNode west = neighbor(value -> value.left, -1, 0, false);
        SceneNode east = neighbor(value -> value.right, 1, 0, false);
        SceneNode result = new SceneNode().size(Size.natural(), Size.natural())
                .control(new MinerHudDialLayout(() -> ui.colors.get().textStyle.logicalEm, navigation,
                        ring, center, north, south, west, east))
                .add(ring).add(center).add(north).add(south).add(west).add(east);
        result.bind(SceneMotion.layoutSize(() -> page.theme().get().spatialMotion));
        result.bind(SceneMotion.layoutPositions(() -> page.theme().get().spatialMotion));
        return result;
    }
    private SceneNode animated(Function<MinerHudSelection, String> text, Function<MinerHudSelection, Object> key,
            float scale, boolean sub) {
        return SceneAnimatedContent.create(page, model.selection, key,
                value -> SceneLayouts.center(ui.ink(() -> text.apply(value.get()), scale,
                        () -> sub ? MinerHudPalette.subtitle(value.get().mode, ui.colors.get())
                                : MinerHudPalette.mode(value.get().mode, ui.colors.get())))
                        .size(Size.natural(), Size.natural()),
                () -> entrance(sub));
    }
    private EntranceOptions entrance(boolean sub) {
        MinerHudSelection selected = model.selection.get();
        float em = ui.colors.get().textStyle.logicalEm;
        return new EntranceOptions(page.theme().get().spatialMotion, page.theme().get().feedbackMotion,
                selected.horizontal * em * 1.6f, sub ? selected.vertical * em * 1.1f : 0);
    }
    private SceneNode neighbor(Function<MinerHudSelection, String> text, int x, int y, boolean sub) {
        ReadableSignal<String> value = () -> text.apply(model.selection.get());
        ReadableSignal<Integer> color = () -> sub ? MinerHudPalette.secondary(model.selection.get().mode, ui.colors.get())
                : MinerHudPalette.secondary(x < 0 ? model.selection.get().previousMode : model.selection.get().nextMode, ui.colors.get());
        SceneNode label = ui.ink(() -> (y < 0 ? "⌃  " : x < 0 ? "‹  " : "") + value.get()
                + (y > 0 ? "  ⌄" : x > 0 ? "  ›" : ""), .72f, color);
        SceneNode item = ui.panel(label, .3f, () -> ui.colors.get().background).bind(MinerHudEntrance.enter(page,
                sub ? MinerHudEntrance.VERTICAL : MinerHudEntrance.HORIZONTAL,
                () -> -x * ui.colors.get().textStyle.logicalEm * .85f,
                () -> -y * ui.colors.get().textStyle.logicalEm * .85f));
        // 入场与导航显隐分别作用于内外节点，避免争用同一透明度/位移通道。
        return part(SceneLayouts.overlay().size(Size.natural(), Size.natural()).add(item), x, y,
                () -> !value.get().isEmpty());
    }
    private SceneNode part(SceneNode node, int x, int y) { return part(node, x, y, () -> true); }
    private SceneNode part(SceneNode node, int x, int y, ReadableSignal<Boolean> available) {
        node.bind(SceneMotion.presence(() -> navigation.get() && available.get(),
                () -> new PresenceOptions(page.theme().get().feedbackMotion)));
        node.bind(SceneMotion.translateX(() -> navigation.get() ? 0f : -x * ui.colors.get().textStyle.logicalEm,
                () -> page.theme().get().spatialMotion));
        node.bind(SceneMotion.translateY(() -> navigation.get() ? 0f : -y * ui.colors.get().textStyle.logicalEm,
                () -> page.theme().get().spatialMotion));
        return node;
    }
}
