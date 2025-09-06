package club.heiqi.qz_miner;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class Config {
    public static String configPath;
    public static Configuration config;

    public static int bigRadius = 8;
    public static int blockLimit = 1024;

    public void init(File configFile) {
        if (config == null) {
            configPath = configFile.getAbsolutePath();
            config = new Configuration(configFile);
        }
        load();
    }

    public void load() {
        bigRadius = config.getInt("bigRadius", Configuration.CATEGORY_GENERAL, 8, 0, Integer.MAX_VALUE, "最大连锁半径");
        blockLimit = config.getInt("blockLimit", Configuration.CATEGORY_GENERAL, 1024, 0, Integer.MAX_VALUE, "最大连锁数量");

        if (config.hasChanged()) {
            config.save();
        }
    }

    public void save() {
        config.save();
    }

    @SubscribeEvent
    public void onConfigChangeEvent(ConfigChangedEvent event) {
        if (event.modID.equalsIgnoreCase(Constant.MODID)) {
            save();
        }
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
