package club.heiqi.qz_miner.client.configGUI;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

/**
 * Forge 原生配置页。
 *
 * <p>该界面作为 UILib 缺失时的兜底实现，避免配置入口强依赖可选 UI 库。</p>
 */
public class QzMinerConfigGUI extends GuiConfig {

    /**
     * 创建模组配置界面。
     *
     * @param parentScreen 父界面
     */
    public QzMinerConfigGUI(GuiScreen parentScreen) {
        super(parentScreen, createConfigElements(), MyMod.MODID, MyMod.MODID, false, false, MyMod.MOD_NAME + " 配置");
    }

    /**
     * 构建 Forge 配置 GUI 使用的配置项。
     *
     * @return 配置 GUI 元素列表
     */
    private static List<IConfigElement> createConfigElements() {
        List<IConfigElement> elements = new ArrayList<IConfigElement>();
        if (Config.config == null) {
            return elements;
        }

        elements.add(new ConfigElement(Config.config.getCategory(Configuration.CATEGORY_GENERAL)));
        elements.add(new ConfigElement(Config.config.getCategory(Config.CATEGORY_CLIENT)));
        return elements;
    }
}
