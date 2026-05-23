package club.heiqi.qz_miner.client.configGUI;

import net.minecraft.client.gui.GuiScreen;

/**
 * 配置界面适配器门面。
 */
public final class ConfigGuiAdapters {

    private static final ConfigGuiAdapter[] ADAPTERS = new ConfigGuiAdapter[] {
            new QzUilibConfigGuiAdapter(),
            new ForgeConfigGuiAdapter()
    };

    private ConfigGuiAdapters() {}

    /**
     * 选择当前环境可用的主配置界面。
     *
     * @return 配置界面类
     */
    public static Class<? extends GuiScreen> getMainConfigGuiClass() {
        for (ConfigGuiAdapter adapter : ADAPTERS) {
            if (adapter.isAvailable()) {
                return adapter.getConfigGuiClass();
            }
        }

        return QzMinerConfigGUI.class;
    }
}
