package club.heiqi.qz_miner.client.configGUI;

import net.minecraft.client.gui.GuiScreen;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.ui.ConfigScreen;
import club.heiqi.config.ui.ConfigUI;
import club.heiqi.config.ui.editor.Registry;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.client.configGUI.objectgroup.ObjectGroupEditorFieldRenderer;
import club.heiqi.qz_miner.client.configGUI.objectgroup.ObjectGroupEditorState;
import club.heiqi.qz_miner.client.picker.ObjectGroupPickerRegistration;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.uilib.ui.scene.host.lwjgl.LwjglInputSource;
import club.heiqi.uilib.ui.scene.host.lwjgl.LwjglStateReader;
import club.heiqi.uilib.ui.scene.input.PlatformInputSource;
import club.heiqi.uilib.ui.screen.McScreenBridge;

/**
 * UILib 4.5 配置页宿主（{@link McScreenBridge}）。
 *
 * <p>满足 IModGuiFactory 单参 {@code (GuiScreen)} 构造契约；使用长寿命
 * {@link ConfigBootstrap#manager()}，不在开屏时重复 bootstrap / 重复订阅。</p>
 */
public class QzMinerConfigGUI extends McScreenBridge {

    /**
     * Forge guiFactory 反射入口。
     *
     * @param parentScreen 父界面
     */
    public QzMinerConfigGUI(GuiScreen parentScreen) {
        super(parentScreen, buildSurface());
    }

    private static ConfigScreen buildSurface() {
        ConfigManager manager = ConfigBootstrap.manager();
        if (manager == null) {
            MyMod.LOG.error("ConfigManager missing when opening config GUI; building ephemeral defaults surface");
            // 防御：不应发生；若发生则用空路径 bootstrap 会在 preInit 已失败后仍尽量不 NPE
            throw new IllegalStateException("ConfigBootstrap.manager() is null; preInit must run first");
        }
        PlatformInputSource input = new LwjglInputSource(new LwjglStateReader());
        // 对象组编辑视图要用与本 screen 同一个已冻结 editor registry（成员 picker 候选源来自它）。
        // buildScreen 先执行 editorRegistryCustomizer、再执行字段 renderer customizer，所以用一个
        // 惰性持有者把该 registry 带进 renderer：render 发生在 ConfigScreen 构造期，届时已冻结。
        final Registry[] editorRegistry = new Registry[1];
        return ConfigUI.buildScreen(manager, input,
                registry -> {
                    registry.registerPath(ObjectGroupEditorState.PATH,
                            new ObjectGroupEditorFieldRenderer(() -> editorRegistry[0]));
                    // 本轮新增配置键的 tooltip 走语言文件（UILib 配置页无 label/helper i18n 通道）；
                    // 控件本身走类型默认渲染器——颜色键由 schema 的 .color() 声明，按 widget 分发到 HEX 输入框
                    PreviewConfigTooltips.install(registry);
                },
                policy -> { },
                editors -> {
                    editorRegistry[0] = editors;
                    ObjectGroupPickerRegistration.register(editors);
                });
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
