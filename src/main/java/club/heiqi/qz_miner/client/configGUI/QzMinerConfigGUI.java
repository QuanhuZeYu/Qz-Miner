package club.heiqi.qz_miner.client.configGUI;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MOD_INFO;
import cpw.mods.fml.client.config.GuiConfig;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.List;

import static club.heiqi.qz_miner.MY_LOG.logger;

public class QzMinerConfigGUI extends GuiConfig {
    public QzMinerConfigGUI(GuiScreen parentScreen) {
        super(
            parentScreen,
            getConfigElements(),
            MOD_INFO.MODID,
            false,
            false,
            MOD_INFO.NAME,
            GuiConfig.getAbridgedConfigPath(Config.configFile));
    }

    private static List getConfigElements() {
        return new ConfigElement(Config.config.getCategory(Configuration.CATEGORY_GENERAL)).getChildElements();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
//        Config.save();
        Config.config.save();
        Config.sync(new File(Config.configFile));
        logger.info("修改后的全局变量为: {radiusLimit: {}, blockLimit: {}}", Config.radiusLimit, Config.blockLimit);
    }
}
