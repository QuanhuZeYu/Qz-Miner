package club.heiqi.qz_miner.client.configGUI;

import net.minecraft.client.gui.GuiScreen;

/**
 * Forge 原生配置界面适配器。
 */
public class ForgeConfigGuiAdapter implements ConfigGuiAdapter {

    /**
     * Forge 原生配置页始终可用。
     *
     * @return 恒为 true
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    /**
     * 获取 Forge 原生配置界面类。
     *
     * @return Forge 原生配置界面类
     */
    @Override
    public Class<? extends GuiScreen> getConfigGuiClass() {
        return QzMinerConfigGUI.class;
    }
}
