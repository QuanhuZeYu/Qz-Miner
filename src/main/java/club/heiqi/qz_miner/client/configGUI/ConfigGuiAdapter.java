package club.heiqi.qz_miner.client.configGUI;

import net.minecraft.client.gui.GuiScreen;

/**
 * 配置界面适配器。
 *
 * <p>用于隔离可选 UI 库，让 GUI 工厂只依赖本模组自身的抽象入口。</p>
 */
public interface ConfigGuiAdapter {

    /**
     * 判断当前运行环境是否可以使用该适配器。
     *
     * @return 可以使用时返回 true
     */
    boolean isAvailable();

    /**
     * 获取 Forge Mod List 应实例化的配置界面类。
     *
     * @return 配置界面类
     */
    Class<? extends GuiScreen> getConfigGuiClass();
}
