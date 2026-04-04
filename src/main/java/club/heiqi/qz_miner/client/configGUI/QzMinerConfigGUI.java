package club.heiqi.qz_miner.client.configGUI;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.client.config.GuiConfig;
import cpw.mods.fml.client.config.IConfigElement;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

public class QzMinerConfigGUI extends GuiConfig {

    public QzMinerConfigGUI(GuiScreen parentScreen) {
        super(
            parentScreen,
            getConfigElements(),
            MyMod.MODID,
            false,
            false,
            MyMod.MOD_NAME,
            Config.configPath == null ? "" : GuiConfig.getAbridgedConfigPath(Config.configPath));
    }

    private static List<IConfigElement> getConfigElements() {
        List<IConfigElement> elements = new ArrayList<>();

        for (String categoryName : Arrays.asList(Configuration.CATEGORY_GENERAL, Config.CATEGORY_CLIENT)) {
            ConfigCategory category = Config.config.getCategory(categoryName);
            elements.add(new ConfigElement(category));
        }

        return elements;
    }
}
