package club.heiqi.qz_miner.client.hud;

import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.motion.PresenceOptions;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;

/** HUD 只声明业务浮层令牌；圆角、边线、字体、主题与动效策略均由正式 Scene 提供。 */
public final class MinerHudAppearance extends SceneAppearance {
    private static final float SURFACE_ALPHA = 0.90f;
    private static final float TITLE_EM = 0.95f;
    private static final float BODY_EM = 0.84f;
    private static final float STATUS_EM = 0.76f;
    private static final float MIN_WIDTH_EM = 10f;
    private static final float MAX_WIDTH_EM = 30f;
    private static final float LABEL_WIDTH_EM = 8f;
    private final ScenePageContext context;
    public MinerHudAppearance(ScenePageContext context) { super(context, context.theme()); this.context = context; }

    public SceneNode content(MinerHudModel model) {
        SceneNode body = column(0).size(Size.natural(), Size.natural());
        body.bind(NodeBinding.value(colors, (node, palette) -> node.padding(palette.source.padding).gap(palette.source.gap)
                .size(Size.natural().bounds(Math.round(palette.textStyle.logicalEm * MIN_WIDTH_EM),
                        Math.round(palette.textStyle.logicalEm * MAX_WIDTH_EM)), Size.natural())));
        SceneNode header = SceneLayouts.wrap(() -> colors.get().source.gap * 2).size(Size.natural(), Size.natural());
        header.add(label(model.heading, TITLE_EM, false));
        SceneNode badge = card(label(model.status, STATUS_EM, true),
                () -> model.expanded.get() ? colors.get().selected : colors.get().surface)
                .bind(NodeBinding.value(colors, (node, palette) -> node.padding(Math.max(2, palette.source.padding / 3))));
        header.add(badge); body.add(header);
        SceneNode values = column(0).size(Size.natural(), Size.natural())
                .bind(NodeBinding.value(colors, (node, palette) -> node.gap(palette.source.gap)));
        values.bind(NodeBinding.repeat(model.rows, row -> row.id, row -> {
            MinerHudAppearance rowAppearance = new MinerHudAppearance(context);
            SceneNode pair = row(0).size(Size.natural(), Size.natural()).own(rowAppearance.scope)
                    .bind(NodeBinding.value(colors, (node, palette) -> node.gap(palette.source.gap * 2)));
            pair.add(rowAppearance.label(() -> row.get().label, BODY_EM, true)
                    .bind(NodeBinding.value(colors, (node, palette) -> node.size(
                            Size.fixed(Math.round(palette.textStyle.logicalEm * LABEL_WIDTH_EM)), Size.natural()))));
            pair.add(rowAppearance.label(() -> row.get().value, BODY_EM, false).size(Size.natural(), Size.natural()));
            return pair;
        }));
        values.bind(SceneMotion.presence(model.expanded, () -> new PresenceOptions(context.theme().get().feedbackMotion)));
        body.add(values);
        SceneNode surface = card(body, () -> SceneMotion.alpha(colors.get().background, SURFACE_ALPHA))
                .size(Size.natural(), Size.natural()).own(scope);
        surface.bind(SceneMotion.layoutSize(() -> context.theme().get().spatialMotion));
        return surface;
    }
}
