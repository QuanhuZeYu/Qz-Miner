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
    public static int smallRadius = 2;

    public static final String ClientCategory = "Client";
    public static boolean usePreview = true;

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
        smallRadius = config.getInt("smallRadius", Configuration.CATEGORY_GENERAL, 2, 0, Integer.MAX_VALUE, "连锁 小区域 检测半径");

        usePreview = config.getBoolean("usePreview", ClientCategory, true, "是否使用连锁预览功能");

        if (config.hasChanged()) {
            config.save();
        }
    }

    @SubscribeEvent
    public void onConfigChangeEvent(ConfigChangedEvent event) {
        if (!event.modID.equalsIgnoreCase(Constant.MODID)) return;
        Constant.LOG.info("保存事件触发");
        load();
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
