package club.heiqi.qz_miner;

import java.io.File;

import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;

public class Config {

    public static String configPath;
    public static Configuration config;
    public static String greeting = "Hello World";

    /**
     * 初始化配置并注册配置变更监听。
     *
     * @param configFile Forge 提供的配置文件
     */
    public void init(File configFile) {
        if (config == null) {
            configPath = configFile.getAbsolutePath();
            config = new Configuration(configFile);
            MinecraftForge.EVENT_BUS.register(this);
            FMLCommonHandler.instance().bus().register(this);
        }

        load();
    }

    /**
     * 从配置文件加载当前配置项。
     */
    public void load() {
        greeting = config.getString("greeting", Configuration.CATEGORY_GENERAL, greeting, "How shall I greet?");

        if (config.hasChanged()) {
            config.save();
        }
    }

    /**
     * 从 Forge 配置界面保存后重新加载配置。
     *
     * @param event 配置变更事件
     */
    @SubscribeEvent
    public void onConfigChangeEvent(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (!MyMod.MODID.equalsIgnoreCase(event.modID)) {
            return;
        }

        load();
    }
}
