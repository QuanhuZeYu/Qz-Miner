package club.heiqi.qz_miner.client.configGUI;

import java.util.HashSet;
import java.util.Set;

import cpw.mods.fml.client.IModGuiFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

/**
 * Qz Miner 客户端配置 GUI 工厂。
 */
public class QzMinerConfigGUIFactory implements IModGuiFactory {

    /**
     * 初始化 GUI 工厂。
     *
     * @param minecraftInstance 当前 Minecraft 实例
     */
    @Override
    public void initialize(Minecraft minecraftInstance) {}

    /**
     * 返回当前环境可用的配置界面。
     *
     * @return 配置界面类
     */
    @Override
    public Class<? extends GuiScreen> mainConfigGuiClass() {
        return ConfigGuiAdapters.getMainConfigGuiClass();
    }

    /**
     * 本模组暂无运行时配置分类。
     *
     * @return 空集合
     */
    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() {
        return new HashSet<>();
    }

    /**
     * 本模组暂无运行时配置处理器。
     *
     * @param element 运行时配置分类
     * @return 恒为 null
     */
    @Override
    public RuntimeOptionGuiHandler getHandlerFor(RuntimeOptionCategoryElement element) {
        return null;
    }
}
