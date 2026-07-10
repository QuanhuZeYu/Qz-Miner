package club.heiqi.qz_miner.client.configGUI;

import net.minecraft.client.gui.GuiScreen;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.ui.ConfigScreen;
import club.heiqi.config.ui.ConfigUI;
import club.heiqi.qz_miner.MyMod;
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
        return ConfigUI.buildScreen(manager, input);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }
}
