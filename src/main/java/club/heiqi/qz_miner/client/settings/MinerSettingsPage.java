package club.heiqi.qz_miner.client.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.ReactiveDomain;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance.Role;
import club.heiqi.uilib.ui.scene.control.SceneIconButton;
import club.heiqi.uilib.ui.scene.control.SceneCommand;
import club.heiqi.uilib.ui.scene.control.SceneDialog;
import club.heiqi.uilib.ui.scene.control.SceneLabel;
import club.heiqi.uilib.ui.scene.control.SceneSelect;
import club.heiqi.uilib.ui.scene.host.ScenePage;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.settings.SettingsFields;
import club.heiqi.uilib.ui.settings.SettingsTemplate;

/** 通用模板只提供工作台；草稿、权限、保存和离开选择由 Miner 负责。 */
public final class MinerSettingsPage implements ScenePage {
    private final ReactiveDomain domain;
    private final MinerSettingsDraft draft;
    private final MinerSettingsSave saver;
    private final Function<String, String> translate;
    private final Function<ConfigKey<?>, String> serverFact;
    private final Runnable back;
    private final Runnable reload;
    private final Runnable editHud;
    private final Signal<String> locale;
    private final Signal<Map<String, String>> errors;
    private final Signal<Map<ConfigKey<?>, String>> serverFacts;
    private final Signal<Boolean> busy;
    private final Signal<Boolean> active;
    private final Signal<Boolean> dirty;
    private final Signal<String> notice;
    private final Signal<Boolean> confirm;
    private final Map<ConfigKey<?>, Signal<?>> values = new LinkedHashMap<ConfigKey<?>, Signal<?>>();
    private Runnable leave;
    private ScenePageContext context;
    private MinerSettingsAppearance appearance;

    public MinerSettingsPage(ReactiveDomain domain, MinerSettingsDraft draft, MinerSettingsSave saver,
            Function<String, String> translate, Function<ConfigKey<?>, String> serverFact,
            Runnable back, Runnable reload, Runnable editHud) {
        this.domain = domain;
        this.draft = draft;
        this.saver = saver;
        this.translate = translate;
        this.serverFact = serverFact;
        this.back = back;
        this.reload = reload;
        this.editHud = editHud;
        locale = domain.signal("");
        errors = domain.signal(Collections.<String, String>emptyMap());
        serverFacts = domain.signal(Collections.<ConfigKey<?>, String>emptyMap());
        refreshServerFacts();
        busy = domain.signal(false);
        active = domain.signal(true);
        dirty = domain.signal(false);
        notice = domain.signal("");
        confirm = domain.signal(false);
    }

    public void locale(String language) { locale.set(language); }
    public void refreshServerFacts() {
        if (draft.access() != MinerSettingsAccess.REMOTE) return;
        Map<ConfigKey<?>, String> facts = new LinkedHashMap<ConfigKey<?>, String>();
        for (ConfigKey<?> key : QzMinerConfigSchema.SCHEMA.keys()) {
            if (MinerSettingsAccess.serverControlled(key)) facts.put(key, serverFact.apply(key));
        }
        serverFacts.set(Collections.unmodifiableMap(facts));
    }
    public void active(boolean current) {
        active.set(current);
        if (!current) notice.set(text("settings.qz_miner.context_changed").get());
    }

    @Override public void build(ScenePageContext context) { context.mountOwned(create(context)); }

    public SceneNode create(ScenePageContext context) {
        this.context = context;
        context.own(saver);
        appearance = new MinerSettingsAppearance(context);
        SettingsTemplate template = new SettingsTemplate(text("settings.qz_miner.title"))
                .description(text("settings.qz_miner.description"))
                .labels(text("settings.qz_miner.close"), text("settings.qz_miner.sections"))
                .notice(() -> !notice.get().isEmpty() ? notice.get() : busy.get() ? text("settings.qz_miner.saving").get()
                        : dirty.get() ? text("settings.qz_miner.dirty").get() : text("settings.qz_miner.ready").get())
                .section("mining", text("settings.qz_miner.mining"), text("settings.qz_miner.mining.help"), SceneIconButton.Symbol.SLIDERS, this::mining)
                .section("preview", text("settings.qz_miner.preview"), text("settings.qz_miner.preview.help"), SceneIconButton.Symbol.MONITOR, this::preview)
                .section("objects", text("config.qz_miner.object_group.editor.title"), text("settings.qz_miner.objects.help"), SceneIconButton.Symbol.FOLDER, c ->
                        new MinerObjectGroupsEditor(c, draft, this::changed, errors, enabled(), this::text).create())
                .section("server", text("settings.qz_miner.server"), text("settings.qz_miner.server.help"), SceneIconButton.Symbol.ARCHIVE, this::server)
                .action(text("settings.qz_miner.save"), SceneCommand.of(() -> !busy.get() && dirty.get(),
                        () -> save(back)), true)
                .action(text("settings.qz_miner.reload"), SceneCommand.of(navigable(),
                        () -> requestLeave(reload)), false)
                .onClose(() -> requestLeave(back));
        SceneNode root = SceneLayouts.overlay().size(Size.fill(1), Size.fill(1)).own(appearance.scope);
        root.add(template.create(context));
        root.add(confirmDialog(context));
        root.onEscape(() -> { requestLeave(back); return true; });
        changed();
        return root;
    }

    private SceneNode mining(ScenePageContext c) {
        SceneNode content = column();
        SceneNode requests = group("requests");
        requests.add(field(QzMinerConfigSchema.CHAIN_RADIUS));
        requests.add(field(QzMinerConfigSchema.CHAIN_MAX_BLOCKS));
        requests.add(field(QzMinerConfigSchema.TUNNEL_DIRECTION_SOURCE));
        requests.add(field(QzMinerConfigSchema.CHAIN_LOGGING_SHELL_LAYERS));
        content.add(requests).add(appearance.line());
        SceneNode tools = group("tools");
        tools.add(appearance.label(() -> source(QzMinerConfigSchema.AUTO_TOOL_SWAP_ENABLED), 0.84f, true));
        tools.add(field(QzMinerConfigSchema.AUTO_TOOL_SWAP_ENABLED));
        if (draft.access().canEdit(QzMinerConfigSchema.AUTO_TOOL_PRIORITY_SELECTORS)) {
            tools.add(new MinerStringListEditor(c, stringList(draft.value(QzMinerConfigSchema.AUTO_TOOL_PRIORITY_SELECTORS)),
                    next -> { draft.set(QzMinerConfigSchema.AUTO_TOOL_PRIORITY_SELECTORS, next); changed(); },
                    enabled(), text("settings.qz_miner.tools"), error("client.autoToolPrioritySelectors"), this::text).create());
        } else tools.add(readOnly(QzMinerConfigSchema.AUTO_TOOL_PRIORITY_SELECTORS));
        return content.add(tools);
    }

    private SceneNode preview(ScenePageContext c) {
        SceneNode content = column();
        SceneNode preview = group("preview");
        for (ConfigKey<?> key : new ConfigKey<?>[] {QzMinerConfigSchema.CLIENT_ENABLE_PREVIEW_RENDER,
                QzMinerConfigSchema.CLIENT_PREVIEW_MAX_RADIUS, QzMinerConfigSchema.CLIENT_PREVIEW_MAX_TARGETS,
                QzMinerConfigSchema.CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP}) preview.add(field(key));
        content.add(preview).add(appearance.line());
        SceneNode hud = group("hud");
        hud.add(field(QzMinerConfigSchema.HUD_ENABLED));
        hud.add(appearance.button(text("hud.qz_miner.edit_action.label"),
                SceneCommand.of(enabled(), () -> requestLeave(editHud)), Role.OUTLINE));
        for (ConfigKey<?> key : new ConfigKey<?>[] {QzMinerConfigSchema.CLIENT_PREVIEW_TRUNCATION_SIGNAL,
                QzMinerConfigSchema.CLIENT_PREVIEW_EXECUTION_PROGRESS, QzMinerConfigSchema.CLIENT_PREVIEW_BACKEND_DIAGNOSTICS})
            hud.add(field(key));
        content.add(hud).add(appearance.line());
        SceneNode visuals = group("visuals");
        for (ConfigKey<?> key : QzMinerConfigSchema.SCHEMA.keys()) {
            String name = key.name();
            if (name.startsWith("client.clientPreview") && key != QzMinerConfigSchema.CLIENT_PREVIEW_MAX_RADIUS
                    && key != QzMinerConfigSchema.CLIENT_PREVIEW_MAX_TARGETS
                    && key != QzMinerConfigSchema.CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP
                    && key != QzMinerConfigSchema.CLIENT_PREVIEW_TRUNCATION_SIGNAL
                    && key != QzMinerConfigSchema.CLIENT_PREVIEW_EXECUTION_PROGRESS
                    && key != QzMinerConfigSchema.CLIENT_PREVIEW_BACKEND_DIAGNOSTICS) visuals.add(field(key));
        }
        return content.add(visuals);
    }

    private SceneNode server(ScenePageContext c) {
        SceneNode content = column();
        content.add(appearance.label(() -> source(QzMinerConfigSchema.TICK_BUDGET_MS), 0.9f, true));
        SceneNode rules = group("rules");
        for (ConfigKey<?> key : QzMinerConfigSchema.SCHEMA.keys()) {
            if (key.name().startsWith("general.") && key != QzMinerConfigSchema.CHAIN_RADIUS
                    && key != QzMinerConfigSchema.CHAIN_MAX_BLOCKS
                    && key != QzMinerConfigSchema.CHAIN_LOGGING_SHELL_LAYERS) rules.add(field(key));
        }
        return content.add(rules);
    }

    private SceneNode group(String id) {
        return appearance.group(text("settings.qz_miner.group." + id), text("settings.qz_miner.group." + id + ".help"));
    }

    private SceneNode field(ConfigKey<?> key) {
        if (!draft.access().canEdit(key)) return readOnly(key);
        SettingsFields fields = new SettingsFields(context);
        ReadableSignal<String> label = label(key);
        ReadableSignal<String> description = hint(key);
        if (key.defaultValue() instanceof Boolean) {
            Signal<Boolean> value = value(key, (Boolean) draft.value(key));
            return fields.toggle(label, description, value, enabled(), error(key.name()));
        }
        List<String> choices = QzMinerConfigSchema.choices(key);
        if (!choices.isEmpty()) {
            Signal<String> value = value(key, (String) draft.value(key));
            List<SceneSelect.Option<String>> options = new ArrayList<SceneSelect.Option<String>>();
            for (String choice : choices) {
                ReadableSignal<String> name = key == QzMinerConfigSchema.HUD_ANCHOR
                        ? text("settings.qz_miner.anchor." + choice) : choiceName(choice);
                options.add(new SceneSelect.Option<String>(choice, name, enabled()));
            }
            return fields.choice(label, description, () -> options, value, enabled(), error(key.name()));
        }
        Signal<String> value = value(key, format(draft.value(key)));
        return fields.text(label, description, value, enabled(), error(key.name()));
    }

    private SceneNode readOnly(ConfigKey<?> key) {
        // 远端只读行显示已同步事实，缺省字段由 serverFacts 显示未知。
        ReadableSignal<String> fact = () -> draft.access() == MinerSettingsAccess.REMOTE
                ? serverFacts.get().get(key) : format(draft.baseline().get(key));
        return new SettingsFields(context).row(label(key), () -> source(key),
                appearance.label(fact, 0.95f, true), () -> "");
    }

    private String source(ConfigKey<?> key) {
        String id = !MinerSettingsAccess.serverControlled(key) ? "local"
                : draft.access() == MinerSettingsAccess.REMOTE ? "remote"
                : draft.access() == MinerSettingsAccess.MENU ? "menu"
                : draft.access() == MinerSettingsAccess.INTEGRATED_ADMIN ? "admin" : "readonly";
        return text("settings.qz_miner.source." + id).get();
    }

    private ReadableSignal<String> label(ConfigKey<?> key) {
        String name = key.name().substring(key.name().indexOf('.') + 1);
        return () -> {
            String id = "config.qz_miner." + name + ".label";
            String translated = text(id).get();
            return translated.equals(id) ? name : translated;
        };
    }

    private ReadableSignal<String> hint(ConfigKey<?> key) {
        String name = key.name().substring(key.name().indexOf('.') + 1);
        return () -> {
            String id = "settings.qz_miner.field." + name;
            String hint = text(id).get();
            return hint.equals(id) ? "" : hint;
        };
    }
    private ReadableSignal<String> choiceName(String choice) {
        return () -> {
            String id = "settings.qz_miner.choice." + choice;
            String name = text(id).get();
            return name.equals(id) ? choice : name;
        };
    }
    static String format(Object value) {
        return value instanceof Double ? java.math.BigDecimal.valueOf((Double) value).stripTrailingZeros().toPlainString()
                : String.valueOf(value);
    }

    @SuppressWarnings("unchecked") private <T> Signal<T> value(ConfigKey<?> key, T initial) {
        Signal<T> existing = (Signal<T>) values.get(key);
        if (existing != null) return existing;
        Signal<T> signal = domain.signal(initial);
        values.put(key, signal);
        domain.effect(context.scope(), () -> { draft.set(key, signal.get()); changed(); });
        return signal;
    }

    void changed() {
        errors.set(draft.validate().errors);
        dirty.set(draft.dirty());
        notice.set("");
    }

    ReadableSignal<Boolean> enabled() { return () -> !busy.get() && active.get(); }
    private ReadableSignal<Boolean> navigable() { return () -> !busy.get() || !active.get(); }
    ReadableSignal<String> error(String path) {
        return () -> {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> error : errors.get().entrySet()) {
                if (error.getKey().startsWith(path)) {
                    if (out.length() > 0) out.append("; ");
                    out.append(error.getValue());
                }
            }
            return out.toString();
        };
    }
    ReadableSignal<String> text(String key) { return () -> { locale.get(); return translate.apply(key); }; }

    private void save(Runnable after) {
        if (!active.get()) { notice.set(text("settings.qz_miner.context_changed").get()); return; }
        changed();
        if (!errors.get().isEmpty()) { notice.set(text("settings.qz_miner.invalid").get()); return; }
        busy.set(true);
        notice.set(text("settings.qz_miner.saving").get());
        boolean started = saver.save(draft, result -> {
            busy.set(false);
            if (result.success()) { notice.set(text("settings.qz_miner.saved").get()); after.run(); }
            else notice.set(text("settings.qz_miner.failed").get() + " " + result.error);
        });
        if (!started) busy.set(false);
    }

    private void requestLeave(Runnable action) {
        if (busy.get() && active.get()) { notice.set(text("settings.qz_miner.saving").get()); return; }
        if (!dirty.get()) { action.run(); return; }
        leave = action;
        confirm.set(true);
    }

    private SceneNode confirmDialog(ScenePageContext c) {
        SceneNode body = appearance.label(text("settings.qz_miner.unsaved"), 1.0f, true);
        SceneNode footer = SceneLayouts.wrap(8);
        footer.add(appearance.button(text("settings.qz_miner.save"), SceneCommand.of(enabled(), () -> {
            confirm.set(false); save(leave);
        }), Role.PRIMARY));
        footer.add(appearance.button(text("settings.qz_miner.discard"), SceneCommand.of(navigable(), () -> {
            confirm.set(false); leave.run();
        }), Role.OUTLINE));
        SceneNode keep = appearance.button(text("settings.qz_miner.keep"), SceneCommand.of(navigable(), () -> confirm.set(false)), Role.GHOST);
        footer.add(keep);
        return SceneDialog.create(c, c.theme(), confirm,
                () -> new SceneDialog.Options("miner-unsaved", 520, 220),
                new SceneDialog.Content(appearance.label(text("settings.qz_miner.title"), 1.1f, false),
                        body, footer, keep, () -> confirm.set(false)));
    }

    private static SceneNode column() { return new SceneNode().size(Size.fill(1), Size.natural()).gap(24); }
    @SuppressWarnings("unchecked") private static List<String> stringList(Object value) { return (List<String>) value; }
}
