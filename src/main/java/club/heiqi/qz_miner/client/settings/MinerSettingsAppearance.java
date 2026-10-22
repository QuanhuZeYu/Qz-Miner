package club.heiqi.qz_miner.client.settings;

import club.heiqi.uilib.ui.reactive.ReadableSignal;
import club.heiqi.uilib.ui.scene.control.SceneAppearance;
import club.heiqi.uilib.ui.scene.control.SceneCommand;
import club.heiqi.uilib.ui.scene.control.SceneTheme;
import club.heiqi.uilib.ui.scene.host.ScenePageContext;
import club.heiqi.uilib.ui.scene.layout.Size;
import club.heiqi.uilib.ui.scene.node.SceneNode;

/** Miner 业务分组与角色声明；颜色、字体、控件反馈继续由正式 SceneAppearance 管理。 */
public final class MinerSettingsAppearance extends SceneAppearance {
    private final ScenePageContext context;
    public MinerSettingsAppearance(ScenePageContext context) { super(context, context.theme()); this.context = context; }

    public ReadableSignal<SceneTheme> editorTheme() { return () -> colors.get().editorTheme(); }

    public SceneNode group(ReadableSignal<String> title, ReadableSignal<String> description) {
        SceneNode group = column(12).size(Size.fill(1), Size.natural());
        group.add(label(title, 1.0f, false));
        group.add(label(description, 0.86f, true));
        return group;
    }

    public SceneNode button(ReadableSignal<String> label, SceneCommand command, Role role) {
        return action(label, (club.heiqi.uilib.ui.scene.control.SceneIconButton.Symbol) null, command, role);
    }
}