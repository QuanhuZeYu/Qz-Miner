package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MOD_INFO;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QzMinerConfigGUI extends GuiConfig {
    public QzMinerConfigGUI(GuiScreen parentScreen) {
        super(parentScreen, getConfigElements(), MOD_INFO.MODID, false, false, MOD_INFO.NAME);
    }

    private static List<IConfigElement> getConfigElements() {
        // 将配置文件的分类添加为配置元素
        List<IConfigElement> configElements = new ArrayList<>();
        List<String> topCategories = new ArrayList<>(Arrays.asList(Configuration.CATEGORY_GENERAL));
        for (String category : topCategories) {
            configElements.add(new ConfigElement(Config.config.getCategory(category)));
        }
        return configElements;
    }
}
