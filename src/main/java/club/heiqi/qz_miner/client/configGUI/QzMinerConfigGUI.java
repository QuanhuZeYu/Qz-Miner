package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MOD_INFO;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QzMinerConfigGUI extends GuiConfig {
    public QzMinerConfigGUI(GuiScreen parentScreen) {
        super(
            parentScreen,
            getConfigElements(),
            MOD_INFO.MODID,
            false,
            false,
            MOD_INFO.NAME,
            GuiConfig.getAbridgedConfigPath(Config.configPath));
    }

    private static List<IConfigElement> getConfigElements() {
        List<IConfigElement> elements = new ArrayList<>();

        List<String> topCategories = Arrays.asList(Configuration.CATEGORY_GENERAL, Config.CATEGORY_CLIENT);
        for (String categoryName : topCategories) {
            ConfigCategory category = Config.config.getCategory(categoryName);
            elements.add(new ConfigElement(category));
        }

        return elements;
    }

    /*@Override
    public void onGuiClosed() {
        super.onGuiClosed();
        for (IConfigElement element : configElements) {

        }
    }*/
}
