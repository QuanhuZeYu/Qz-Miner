package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.qz_miner.MyMod;
import net.minecraft.client.gui.GuiScreen;

/**
 * Qz-UILib 配置界面适配器。
 *
 * <p>通过类名检测和延迟加载隔离 UILib，避免缺少 UILib 时触发类加载错误。</p>
 */
public class QzUilibConfigGuiAdapter implements ConfigGuiAdapter {

    private static final String UILIB_TEMPLATE_CLASS = "club.heiqi.uilib.config.ForgeConfigTemplateScreen";
    private static final String UILIB_GUI_CLASS = "club.heiqi.qz_miner.client.configGUI.uilib.QzMinerUilibConfigGUI";

    /**
     * 判断 UILib 模板与本模组 UILib 界面类是否都可加载。
     *
     * @return UILib 可用时返回 true
     */
    @Override
    public boolean isAvailable() {
        return isClassPresent(UILIB_TEMPLATE_CLASS) && isClassPresent(UILIB_GUI_CLASS);
    }

    /**
     * 获取 UILib 配置界面类。
     *
     * @return UILib 配置界面类；加载失败时回落到 Forge 原生界面
     */
    @Override
    public Class<? extends GuiScreen> getConfigGuiClass() {
        try {
            Class<?> guiClass = Class.forName(UILIB_GUI_CLASS, false, getClass().getClassLoader());
            return guiClass.asSubclass(GuiScreen.class);
        } catch (ClassNotFoundException e) {
            MyMod.LOG.warn("Qz-UILib config GUI class is not present, fallback to Forge config GUI.", e);
        } catch (ClassCastException e) {
            MyMod.LOG.warn("Qz-UILib config GUI class is not a GuiScreen, fallback to Forge config GUI.", e);
        } catch (LinkageError e) {
            MyMod.LOG.warn("Qz-UILib config GUI cannot be linked, fallback to Forge config GUI.", e);
        }

        return QzMinerConfigGUI.class;
    }

    /**
     * 判断指定类是否可以安全加载。
     *
     * @param className 类名
     * @return 可以加载时返回 true
     */
    private boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (LinkageError e) {
            return false;
        }
    }
}
