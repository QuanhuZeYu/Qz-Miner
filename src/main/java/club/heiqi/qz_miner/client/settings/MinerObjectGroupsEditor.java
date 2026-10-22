package club.heiqi.qz_miner.client.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.uilib.ui.reactive.Owner;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance.Role;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.control.SceneCommand;
import club.heiqi.uilib.ui.scene.control.SceneLabel;
import club.heiqi.uilib.ui.scene.control.SceneSelect;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.settings.SettingsFields;

/** ID、modes、members 的结构化编辑，合法域与 wire 限额全部交回真实业务 parser。 */
final class MinerObjectGroupsEditor {
    private final ScenePageContext context;
    private final MinerSettingsDraft draft;
    private final MinerSettingsAppearance appearance;
    private final Runnable changed;
    private final ReadableSignal<Map<String, String>> errors;
    private final ReadableSignal<Boolean> enabled;
    private final Function<String, ReadableSignal<String>> text;
    private final Signal<List<Group>> groups;
    private final Signal<Group> selected;

    MinerObjectGroupsEditor(ScenePageContext context, MinerSettingsDraft draft, Runnable changed,
            ReadableSignal<Map<String, String>> errors, ReadableSignal<Boolean> enabled,
            Function<String, ReadableSignal<String>> text) {
        this.context = context;
        appearance = new MinerSettingsAppearance(context);
        this.draft = draft;
        this.changed = changed;
        this.errors = errors;
        this.enabled = enabled;
        this.text = text;
        List<Group> initial = new ArrayList<Group>();
        for (Map<String, Object> raw : groups(draft.value(QzMinerConfigSchema.OBJECT_GROUPS))) initial.add(new Group(raw));
        groups = context.domain().signal(Collections.unmodifiableList(initial));
        selected = context.domain().signal(initial.isEmpty() ? null : initial.get(0));
    }

    SceneNode create() {
        SceneNode root = new SceneNode().size(Size.fill(1), Size.natural()).gap(12).own(appearance.scope);
        Owner owner = context.scope().child(); root.own(owner);
        root.add(new SettingsFields(context).choice(text.apply("settings.qz_miner.objects.selected"), () -> "", () -> {
            List<SceneSelect.Option<Group>> options = new ArrayList<SceneSelect.Option<Group>>();
            for (Group group : groups.get()) options.add(new SceneSelect.Option<Group>(group,
                    () -> group.id.get().isEmpty() ? text.apply("settings.qz_miner.objects.unnamed").get() : group.id.get(), enabled));
            return options;
        }, selected, enabled, () -> ""));
        SceneNode actions = SceneLayouts.wrap(8);
        actions.add(appearance.button(text.apply("config.qz_miner.object_group.list.add"),
                SceneCommand.of(() -> enabled.get() && groups.get().size() < club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet.MAX_GROUPS, () -> {
                    Map<String, Object> raw = new LinkedHashMap<String, Object>();
                    raw.put("id", ""); raw.put("modes", Collections.emptyList()); raw.put("members", Collections.emptyList());
                    Group group = new Group(raw);
                    List<Group> next = new ArrayList<Group>(groups.get()); next.add(group);
                    groups.set(Collections.unmodifiableList(next)); selected.set(group);
                }), Role.OUTLINE));
        actions.add(appearance.button(text.apply("settings.qz_miner.objects.remove_group"),
                SceneCommand.of(() -> enabled.get() && selected.get() != null, () -> {
                    List<Group> next = new ArrayList<Group>(groups.get()); next.remove(selected.get());
                    groups.set(Collections.unmodifiableList(next)); selected.set(next.isEmpty() ? null : next.get(0));
                }), Role.GHOST));
        root.add(actions).add(appearance.line());
        root.add(new SceneNode().size(Size.fill(1), Size.natural()).bind(NodeBinding.repeat(
                () -> selected.get() == null ? Collections.<Group>emptyList() : Collections.singletonList(selected.get()),
                group -> group, group -> detail(group.get()))));
        root.add(appearance.error(() -> {
            StringBuilder message = new StringBuilder();
            for (Map.Entry<String, String> error : errors.get().entrySet()) {
                if (error.getKey().startsWith("client.objectGroups")) {
                    if (message.length() > 0) message.append("; ");
                    message.append(error.getValue());
                }
            }
            return message.toString();
        }));
        context.domain().effect(owner, () -> {
            List<Map<String, Object>> raw = new ArrayList<Map<String, Object>>();
            for (Group group : groups.get()) {
                Map<String, Object> values = new LinkedHashMap<String, Object>();
                values.put("id", group.id.get());
                List<String> modes = new ArrayList<String>();
                for (Map.Entry<String, Signal<Boolean>> mode : group.modes.entrySet()) {
                    if (mode.getValue().get()) modes.add(mode.getKey());
                }
                values.put("modes", modes); values.put("members", group.members.get()); raw.add(values);
            }
            draft.set(QzMinerConfigSchema.OBJECT_GROUPS, raw); changed.run();
        });
        return root;
    }

    private SceneNode detail(Group group) {
        SettingsFields fields = new SettingsFields(context);
        SceneNode detail = new SceneNode().size(Size.fill(1), Size.natural()).gap(8);
        detail.add(fields.text(text.apply("config.qz_miner.object_group.id.label"),
                text.apply("config.qz_miner.object_group.id.helper"), group.id, enabled, () -> ""));
        detail.add(appearance.line());
        detail.add(appearance.label(text.apply("config.qz_miner.object_group.modes.label"), 1.0f, false));
        detail.add(appearance.label(text.apply("config.qz_miner.object_group.modes.none"), 0.86f, true));
        SceneNode modes = SceneLayouts.wrap(12);
        for (Map.Entry<String, Signal<Boolean>> mode : group.modes.entrySet()) {
            modes.add(fields.toggle(text.apply("config.qz_miner.object_group.mode." + mode.getKey()),
                    () -> "", mode.getValue(), enabled, () -> "").size(Size.fixed(260), Size.natural()));
        }
        detail.add(modes).add(appearance.line());
        detail.add(new MinerStringListEditor(context, group.members.get(), group.members::set, enabled,
                text.apply("config.qz_miner.object_group.members.label"), () -> "", text).create());
        return detail;
    }

    private final class Group {
        final Signal<String> id;
        final Map<String, Signal<Boolean>> modes = new LinkedHashMap<String, Signal<Boolean>>();
        final Signal<List<String>> members;
        Group(Map<String, Object> raw) {
            id = context.domain().signal((String) raw.get("id"));
            List<String> selectedModes = strings(raw.get("modes"));
            for (String mode : ObjectGroupMode.ids()) modes.put(mode, context.domain().signal(selectedModes.contains(mode)));
            members = context.domain().signal(Collections.unmodifiableList(new ArrayList<String>(strings(raw.get("members")))));
        }
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> groups(Object raw) {
        return (List<Map<String, Object>>) raw;
    }
    @SuppressWarnings("unchecked") private static List<String> strings(Object raw) {
        return raw == null ? Collections.<String>emptyList() : (List<String>) raw;
    }
}