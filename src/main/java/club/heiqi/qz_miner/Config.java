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
    public static int chainRadius = 4;
    public static int chainMaxBlocks = 256;
    public static int maxBreakPerTick = 16;
    public static int parallelTickMinDurationMs = 15;
    public static int clientPreviewMaxRadius = 4;

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
        chainRadius = config.getInt("chainRadius", Configuration.CATEGORY_GENERAL, chainRadius, 1, Integer.MAX_VALUE, "连锁范围半径（方盒子半径，搜索顺序仍为中心扩散）");
        chainMaxBlocks = config.getInt("chainMaxBlocks", Configuration.CATEGORY_GENERAL, chainMaxBlocks, 1, Integer.MAX_VALUE, "最大连锁数量");
        maxBreakPerTick = config.getInt("maxBreakPerTick", Configuration.CATEGORY_GENERAL, maxBreakPerTick, 1, Integer.MAX_VALUE, "每 Tick 最多执行的连锁挖掘数量");
        parallelTickMinDurationMs = config.getInt("parallelTickMinDurationMs", Configuration.CATEGORY_GENERAL, parallelTickMinDurationMs, 10, Integer.MAX_VALUE, "同步执行器每刻最短执行时间（毫秒），默认 15，最低 10");
        clientPreviewMaxRadius = config.getInt("clientPreviewMaxRadius", Configuration.CATEGORY_GENERAL, clientPreviewMaxRadius, 1, Integer.MAX_VALUE, "客户端最大预览半径；实际预览范围取该值与 chainRadius 的较小值，避免大范围预览渲染导致卡顿");

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
