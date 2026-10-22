package club.heiqi.qz_miner.client.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import club.heiqi.uilib.ui.reactive.Owner;
import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance.Role;
import club.heiqi.uilib.ui.scene.layout.SceneLayouts;
import club.heiqi.uilib.ui.scene.control.SceneCommand;
import club.heiqi.uilib.ui.scene.control.SceneLabel;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.NodeBinding;
import club.heiqi.uilib.ui.settings.SettingsFields;

/** 有序 selector 列表用正式文本字段逐项编辑，分页限制活动 Scene 控件数量。 */
final class MinerStringListEditor {
    private static final int PAGE_SIZE = 12;
    private final ScenePageContext context;
    private final Signal<List<Entry>> entries;
    private final MinerSettingsAppearance appearance;
    private final Signal<Integer> page;
    private final Consumer<List<String>> changed;
    private final ReadableSignal<Boolean> enabled;
    private final ReadableSignal<String> title;
    private final ReadableSignal<String> error;
    private final Function<String, ReadableSignal<String>> text;

    MinerStringListEditor(ScenePageContext context, List<String> initial, Consumer<List<String>> changed,
            ReadableSignal<Boolean> enabled, ReadableSignal<String> title, ReadableSignal<String> error,
            Function<String, ReadableSignal<String>> text) {
        this.context = context;
        appearance = new MinerSettingsAppearance(context);
        this.changed = changed;
        this.enabled = enabled;
        this.title = title;
        this.error = error;
        this.text = text;
        List<Entry> values = new ArrayList<Entry>();
        for (String item : initial) values.add(new Entry(context.domain().signal(item)));
        entries = context.domain().signal(Collections.unmodifiableList(values));
        page = context.domain().signal(0);
    }

    SceneNode create() {
        SceneNode root = new SceneNode().size(Size.fill(1), Size.natural()).gap(8).own(appearance.scope);
        Owner owner = context.scope().child();
        root.own(owner);
        root.add(appearance.label(title, 1.0f, false));
        SceneNode list = new SceneNode().size(Size.fill(1), Size.natural()).gap(8);
        list.bind(NodeBinding.repeat(() -> {
            List<Entry> all = entries.get();
            int start = Math.min(page.get() * PAGE_SIZE, all.size());
            return all.subList(start, Math.min(start + PAGE_SIZE, all.size()));
        }, entry -> entry, entry -> {
            Entry item = entry.get();
            SceneNode row = new SceneNode().axis(SceneNode.Axis.ROW).size(Size.fill(1), Size.natural()).gap(8);
            SceneNode field = new SettingsFields(context).text(() -> String.valueOf(entries.get().indexOf(item) + 1), () -> "", item.value,
                    enabled, () -> "");
            row.add(field.size(Size.fill(1), Size.natural()));
            row.add(appearance.button(text.apply("config.qz_miner.object_group.list.move_up"),
                    SceneCommand.of(() -> enabled.get() && entries.get().indexOf(item) > 0, () -> move(item, -1)), Role.GHOST));
            row.add(appearance.button(text.apply("config.qz_miner.object_group.list.move_down"),
                    SceneCommand.of(() -> enabled.get() && entries.get().indexOf(item) < entries.get().size() - 1,
                            () -> move(item, 1)), Role.GHOST));
            row.add(appearance.button(text.apply("config.qz_miner.object_group.members.remove"),
                    SceneCommand.of(enabled, () -> {
                        List<Entry> next = new ArrayList<Entry>(entries.get()); next.remove(item);
                        entries.set(Collections.unmodifiableList(next));
                        page.set(Math.min(page.get(), Math.max(0, (next.size() - 1) / PAGE_SIZE)));
                    }), Role.GHOST));
            return row;
        }));
        root.add(list);
        SceneNode actions = SceneLayouts.wrap(8);
        actions.add(appearance.button(text.apply("config.qz_miner.object_group.members.add"),
                SceneCommand.of(enabled, () -> {
                    List<Entry> next = new ArrayList<Entry>(entries.get());
                    next.add(new Entry(context.domain().signal("")));
                    entries.set(Collections.unmodifiableList(next)); page.set((next.size() - 1) / PAGE_SIZE);
                }), Role.OUTLINE));
        actions.add(appearance.button(text.apply("config.qz_miner.object_group.members.page_prev"),
                SceneCommand.of(() -> enabled.get() && page.get() > 0, () -> page.set(page.get() - 1)), Role.GHOST));
        actions.add(appearance.button(text.apply("config.qz_miner.object_group.members.page_next"),
                SceneCommand.of(() -> enabled.get() && (page.get() + 1) * PAGE_SIZE < entries.get().size(),
                        () -> page.set(page.get() + 1)), Role.GHOST));
        root.add(actions);
        root.add(appearance.error(error));
        context.domain().effect(owner, () -> {
            List<String> values = new ArrayList<String>();
            for (Entry entry : entries.get()) values.add(entry.value.get());
            changed.accept(Collections.unmodifiableList(values));
        });
        return root;
    }

    private void move(Entry item, int direction) {
        List<Entry> next = new ArrayList<Entry>(entries.get());
        int index = next.indexOf(item);
        int target = index + direction;
        if (index < 0 || target < 0 || target >= next.size()) return;
        Collections.swap(next, index, target);
        entries.set(Collections.unmodifiableList(next)); page.set(target / PAGE_SIZE);
    }

    private static final class Entry {
        final Signal<String> value;
        Entry(Signal<String> value) { this.value = value; }
    }
}