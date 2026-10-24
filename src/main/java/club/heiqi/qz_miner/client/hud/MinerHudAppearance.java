package club.heiqi.qz_miner.client.hud;

import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneAnimatedContent;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.control.SceneLabel;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.motion.EntranceOptions;
import club.heiqi.uilib.ui.scene.motion.PresenceOptions;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.scene.text.FontStyle;

/** 业务决定何时展示，Core 持有短留期限、轨迹、退场快照及挂载释放。 */
public final class MinerHudAppearance extends SceneAppearance {
    private static final long RESULT_LINGER_NANOS = 3_200_000_000L;
    private static final long NAVIGATION_LINGER_NANOS = 750_000_000L;
    private final ScenePageContext context;
    public MinerHudAppearance(ScenePageContext context) { super(context, context.theme()); this.context = context; }

    public SceneNode content(MinerHudModel model, boolean editing) {
        Signal<Boolean> recent = context.domain().signal(false), recentNavigation = context.domain().signal(false);
        ReadableSignal<Boolean> navigation = () -> editing || model.armed.get() || recentNavigation.get();
        SceneNode outer = SceneLayouts.overlay().size(Size.natural(), Size.natural()).own(scope);
        outer.bind(NodeBinding.transientValue(model.activity, RESULT_LINGER_NANOS, (node, value) -> recent.set(value != null)));
        outer.bind(NodeBinding.transientValue(model.navigation, NAVIGATION_LINGER_NANOS, (node, value) -> recentNavigation.set(value != null)));
        outer.add(SceneAnimatedContent.create(context,
                () -> editing || model.sustained.get() || recent.get() ? model : null, value -> "hud",
                value -> body(value.get(), navigation, editing),
                () -> new EntranceOptions(context.theme().get().spatialMotion, context.theme().get().feedbackMotion,
                        0, colors.get().textStyle.logicalEm * .7f)));
        return outer;
    }
    private SceneNode body(MinerHudModel model, ReadableSignal<Boolean> navigation, boolean editing) {
        SceneNode body = SceneLayouts.centeredColumn(() -> Math.round(colors.get().textStyle.logicalEm * .5f));
        body.add(new MinerHudDial(this, context, model, navigation).create());
        SceneNode status = SceneAnimatedContent.create(context, model.status, value -> value,
                value -> ink(() -> (model.warning.get() ? "!  " : "•  ") + value.get(), .74f,
                        () -> model.warning.get() ? MinerHudPalette.warning(colors.get()) : colors.get().muted),
                () -> new EntranceOptions(context.theme().get().spatialMotion, context.theme().get().feedbackMotion,
                        0, colors.get().textStyle.logicalEm * .45f));
        SceneNode badge = panel(status, .4f, () -> colors.get().background).bind(MinerHudEntrance.enter(context, MinerHudEntrance.STATUS,
                () -> 0f, () -> colors.get().textStyle.logicalEm * .4f));
        body.add(badge);
        SceneNode details = panel(rows(model), .65f)
                .bind(MinerHudEntrance.enter(context, MinerHudEntrance.DETAILS,
                        () -> 0f, () -> colors.get().textStyle.logicalEm * .5f))
                .bind(SceneMotion.presence(() -> (editing || model.expanded.get()) && !model.rows.get().isEmpty(),
                        () -> new PresenceOptions(context.theme().get().feedbackMotion)));
        body.add(details);
        body.bind(SceneMotion.layoutSize(() -> context.theme().get().spatialMotion));
        return body;
    }
    private SceneNode rows(MinerHudModel model) {
        SceneNode values = column(0).size(Size.natural(), Size.natural())
                .bind(NodeBinding.value(colors, (node, palette) -> node.gap(Math.round(palette.textStyle.logicalEm * .3f))));
        values.bind(NodeBinding.repeat(model.rows, row -> row.id, row -> {
            SceneNode pair = row(0).size(Size.natural(), Size.natural())
                    .bind(NodeBinding.value(colors, (node, palette) -> node.gap(palette.source.gap)));
            pair.add(label(() -> row.get().label, .68f, true));
            pair.add(ink(() -> row.get().value, .78f, () -> colors.get().text));
            return pair;
        }));
        return values;
    }
    SceneNode panel(SceneNode content, float padding) {
        return panel(content, padding, () -> colors.get().surface);
    }
    SceneNode panel(SceneNode content, float padding, ReadableSignal<Integer> background) {
        return card(content, background)
                .size(Size.natural(), Size.natural())
                .bind(NodeBinding.value(colors, (node, palette) -> node.padding(Math.round(palette.textStyle.logicalEm * padding))));
    }
    SceneNode ink(ReadableSignal<String> text, float scale, ReadableSignal<Integer> color) {
        ReadableSignal<club.heiqi.uilib.ui.scene.text.TextStyle> typography = () ->
                colors.get().theme(scale, colors.get().text, 0).textStyle
                        .withFontStyle(new FontStyle(scale >= 1 ? 600 : 400, FontStyle.Posture.NORMAL));
        return SceneLabel.create(context, text, () -> colors.get().theme(scale, colors.get().text, 0))
                .size(Size.natural(), Size.natural())
                .bind(NodeBinding.value(typography, (node, style) -> node.style(style.withColor(node.style().color))))
                .bind(SceneMotion.color(color, () -> context.theme().get().feedbackMotion, "minerInk.color",
                        (node, value) -> node.style(typography.get().withColor(value))));
    }
}
