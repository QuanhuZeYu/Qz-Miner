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
    public static int tunnelWidth = 1;

    public static final String CLIENT_CATEGORY = "Client";
    public static boolean usePreview = true, useChainDoneMessage = true;
    public static double addExhaustion;

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
        tunnelWidth = config.getInt("tunnelWidth", Configuration.CATEGORY_GENERAL, 1, 0, Integer.MAX_VALUE, "隧道半径");
        addExhaustion = config.get(CLIENT_CATEGORY, "addExhaustion", 0.025, "每次挖掘增加的饥饿值", -Double.MAX_VALUE, Double.MAX_VALUE).getDouble();

        usePreview = config.getBoolean("usePreview", CLIENT_CATEGORY, true, "是否使用连锁预览功能");
        useChainDoneMessage = config.getBoolean("useChainDoneMessage", CLIENT_CATEGORY, true, "是否使用连锁后的消息提示");

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
