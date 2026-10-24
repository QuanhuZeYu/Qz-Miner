package club.heiqi.qz_miner.client.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import club.heiqi.qz_miner.client.settings.MinerSettingsAccess;
import club.heiqi.qz_miner.client.settings.MinerSettingsAppearance;
import club.heiqi.qz_miner.client.settings.MinerSettingsDraft;
import club.heiqi.qz_miner.client.settings.MinerSettingsSave;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance.Role;
import club.heiqi.uilib.ui.scene.control.SceneCommand;
import club.heiqi.uilib.ui.scene.control.SceneDialog;
import club.heiqi.uilib.ui.scene.control.SceneSelect;
import club.heiqi.uilib.ui.scene.host.ScenePage;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.hud.SceneHud;
import club.heiqi.uilib.ui.scene.hud.SceneHudPlacement;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.motion.PresenceOptions;
import club.heiqi.uilib.ui.scene.motion.SceneMotion;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.settings.SettingsFields;

/** 编辑画布始终为全视口；现代工具浮层可收起，锚点与真实游戏 HUD 保持同一参考。 */
public final class MinerHudLayoutPage implements ScenePage {
    final ReactiveDomain domain;
    final MinerSettingsDraft draft;
    final SceneHudPlacement initial;
    final Signal<SceneHudPlacement> placement;
    final Signal<Integer> snap;
    final Signal<String> scale;
    final Signal<String> notice;
    final Signal<Boolean> busy;
    final Signal<Boolean> confirm;
    final Signal<Boolean> toolsOpen;
    private final Signal<String> language;
    final Signal<SceneHudPlacement.Anchor> anchor;
    final MinerSettingsSave saver;
    final BooleanSupplier current;
    final Function<String, String> translate;
    final Runnable back;
    private final MinerHudModel preview;
    private MinerSettingsAppearance appearance;

    public MinerHudLayoutPage(ReactiveDomain domain, ConfigDocument baseline, MinerSettingsSave saver,
            BooleanSupplier current, Function<String, String> translate, Runnable back) {
        this(domain, baseline, saver, current, translate, back, new MinerHudModel(domain, translate));
    }
    public MinerHudLayoutPage(ReactiveDomain domain, ConfigDocument baseline, MinerSettingsSave saver,
            BooleanSupplier current, Function<String, String> translate, Runnable back,
            MinerHudModel preview) {
        this.domain = domain; this.saver = saver;
        this.current = current; this.translate = translate; this.back = back; this.preview = preview;
        draft = new MinerSettingsDraft(baseline, MinerSettingsAccess.REMOTE);
        initial = MinerHudPlacement.read(baseline);
        placement = domain.signal(initial); snap = domain.signal(8); anchor = domain.signal(initial.anchor);
        scale = domain.signal(number(baseline.get(QzMinerConfigSchema.HUD_SCALE))); notice = domain.signal("");
        busy = domain.signal(false); confirm = domain.signal(false); toolsOpen = domain.signal(true); language = domain.signal("");
    }

    @Override public void build(ScenePageContext c) {
        c.own(saver); appearance = new MinerSettingsAppearance(c);
        SceneNode root = SceneLayouts.overlay().size(Size.fill(1), Size.fill(1)).own(appearance.scope);
        root.add(SceneHud.edit(c, preview.content(c), placement, snap)
                .bind(NodeBinding.value(() -> !busy.get(), SceneNode::enabled)));
        SceneNode panel = tools(c);
        panel.bind(SceneMotion.presence(toolsOpen, () -> new PresenceOptions(c.theme().get().feedbackMotion)));
        SceneNode toggle = appearance.button(() -> text(toolsOpen.get() ? "settings.qz_miner.hud.hide_tools"
                        : "settings.qz_miner.hud.show_tools").get(),
                SceneCommand.of(() -> !busy.get(), () -> toolsOpen.set(!toolsOpen.get())), Role.OUTLINE);
        SceneNode toolsColumn = new SceneNode().size(Size.natural(), Size.fill(1)).gap(8).padding(12);
        toolsColumn.add(toggle).add(panel);
        SceneNode toolsLayer = new SceneNode().axis(SceneNode.Axis.ROW).size(Size.fill(1), Size.fill(1));
        toolsLayer.add(new SceneNode().size(Size.fill(1), Size.fill(1))).add(toolsColumn);
        root.add(toolsLayer).add(dialog(c));
        root.onEscape(() -> { if (!busy.get()) { if (dirty()) confirm.set(true); else back(); } return true; });
        c.mountOwned(root);
    }

    private SceneNode tools(ScenePageContext c) {
        SettingsFields fields = new SettingsFields(c);
        SceneNode controls = new SceneNode().size(Size.fill(1), Size.fill(1)).gap(14).padding(16).scrollable(true);
        controls.add(appearance.label(text("hud.qz_miner.edit_action.label"), 1.1f, false));
        controls.add(appearance.label(text("settings.qz_miner.hud.help"), 0.86f, true)).add(appearance.line());
        List<SceneSelect.Option<SceneHudPlacement.Anchor>> anchors = new ArrayList<SceneSelect.Option<SceneHudPlacement.Anchor>>();
        for (SceneHudPlacement.Anchor choice : SceneHudPlacement.Anchor.values()) anchors.add(new SceneSelect.Option<SceneHudPlacement.Anchor>(
                choice, text("settings.qz_miner.anchor." + choice.name())));
        controls.add(fields.choice(text("config.qz_miner.hudAnchor.label"), () -> "", () -> anchors,
                anchor, () -> !busy.get(), () -> ""));
        controls.add(fields.text(text("config.qz_miner.hudScale.label"), () -> "", scale, () -> !busy.get(), () -> ""));
        domain.effect(c.scope(), () -> {
            SceneHudPlacement.Anchor selected = anchor.get();
            String raw = scale.get();
            try {
                double value = Double.parseDouble(raw);
                draft.baseline().withAll(java.util.Collections.singletonMap(QzMinerConfigSchema.HUD_SCALE, value));
                SceneHudPlacement p = placement.get();
                if (p.scale != (float) value || p.anchor != selected)
                    placement.set(new SceneHudPlacement(selected, p.offsetX, p.offsetY, (float) value));
            } catch (IllegalArgumentException ignored) { /* 非法文字保留，保存时由正式 schema 拒绝。 */ }
        });
        List<SceneSelect.Option<Integer>> snapping = new ArrayList<SceneSelect.Option<Integer>>();
        for (int step : new int[] {0, 4, 8, 16}) snapping.add(new SceneSelect.Option<Integer>(step, () -> String.valueOf(step)));
        controls.add(fields.choice(text("settings.qz_miner.hud.snap"), () -> "", () -> snapping, snap, () -> !busy.get(), () -> ""));
        controls.add(appearance.line());
        controls.add(appearance.button(text("settings.qz_miner.hud.defaults"), SceneCommand.of(() -> !busy.get(), () -> {
            SceneHudPlacement defaults = MinerHudPlacement.defaults();
            domain.batch(() -> { anchor.set(defaults.anchor); scale.set(number(QzMinerConfigSchema.HUD_SCALE.defaultValue())); placement.set(defaults); });
        }), Role.OUTLINE));
        SceneNode actions = SceneLayouts.wrap(8);
        actions.add(appearance.button(text("settings.qz_miner.save"), SceneCommand.of(() -> !busy.get(), this::save), Role.PRIMARY));
        actions.add(appearance.button(text("settings.qz_miner.discard"), SceneCommand.of(() -> !busy.get(), this::back), Role.GHOST));
        controls.add(actions).add(appearance.label(notice, 0.84f, true));
        SceneNode card = appearance.card(controls).size(Size.fixed(320), Size.fill(1));
        card.bind(NodeBinding.value(c.viewportWidth(), (node, width) -> node.size(Size.fixed(Math.min(320, Math.max(180, width - 32))), Size.fill(1))));
        return card;
    }

    boolean dirty() {
        try { return !placement.get().equals(initial) || Double.compare(Double.parseDouble(scale.get()), draft.baseline().get(QzMinerConfigSchema.HUD_SCALE)) != 0; }
        catch (NumberFormatException invalid) { return true; }
    }
    void save() {
        if (!current.getAsBoolean()) { notice.set(text("settings.qz_miner.context_changed").get()); return; }
        for (Map.Entry<ConfigKey<?>, Object> update : MinerHudPlacement.updates(draft.baseline(), placement.get()).entrySet()) draft.set(update.getKey(), update.getValue());
        draft.set(QzMinerConfigSchema.HUD_SCALE, scale.get());
        busy.set(true); notice.set(text("settings.qz_miner.saving").get());
        boolean started = saver.save(draft, result -> {
            busy.set(false);
            if (result.success()) back(); else notice.set(text("settings.qz_miner.failed").get() + " " + result.error);
        });
        if (!started) busy.set(false);
    }
    void back() { back.run(); }
    public void locale(String currentLanguage) { language.set(currentLanguage); }
    ReadableSignal<String> text(String key) { return () -> { language.get(); return translate.apply(key); }; }
    private static String number(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
    SceneNode dialog(ScenePageContext c) {
        SceneNode actions = SceneLayouts.wrap(8);
        actions.add(appearance.button(text("settings.qz_miner.save"), SceneCommand.of(() -> !busy.get(), () -> {
            confirm.set(false); save();
        }), Role.PRIMARY));
        actions.add(appearance.button(text("settings.qz_miner.discard"), SceneCommand.of(() -> !busy.get(), this::back), Role.OUTLINE));
        SceneNode keep = appearance.button(text("settings.qz_miner.keep"), SceneCommand.of(() -> !busy.get(), () -> confirm.set(false)), Role.GHOST);
        actions.add(keep);
        return SceneDialog.create(c, c.theme(), confirm, () -> new SceneDialog.Options("miner-hud-unsaved", 520, 220),
                new SceneDialog.Content(appearance.label(text("hud.qz_miner.edit_action.label"), 1.1f, false),
                        appearance.label(text("settings.qz_miner.unsaved"), 1.0f, true), actions, keep, () -> confirm.set(false)));
    }
}
