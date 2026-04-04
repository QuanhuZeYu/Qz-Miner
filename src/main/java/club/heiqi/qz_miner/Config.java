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
    public static int chainLoggingShellLayers = 1;
    public static int maxBreakPerTick = 16;
    public static int parallelTickMinDurationMs = 15;
    public static int clientPreviewMaxRadius = 4;
    public static int clientPreviewMaxTargets = 256;
    public static double clientPreviewAlphaFadeStartRadius = 2.0D;
    public static double clientPreviewAlphaFadeEndRadius = 6.0D;
    public static double clientPreviewAlphaStartValue = 0.78D;
    public static double clientPreviewAlphaEndValue = 0.15D;

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
        chainLoggingShellLayers = config.getInt("chainLoggingShellLayers", Configuration.CATEGORY_GENERAL, chainLoggingShellLayers, 1, Integer.MAX_VALUE, "CHAIN 伐木子模式每次向外扩展的壳层数；1 表示围绕当前原木检查一圈 3x3x3 邻域");
        maxBreakPerTick = config.getInt("maxBreakPerTick", Configuration.CATEGORY_GENERAL, maxBreakPerTick, 1, Integer.MAX_VALUE, "每 Tick 最多执行的连锁挖掘数量");
        parallelTickMinDurationMs = config.getInt("parallelTickMinDurationMs", Configuration.CATEGORY_GENERAL, parallelTickMinDurationMs, 10, Integer.MAX_VALUE, "同步执行器每刻最短执行时间（毫秒），默认 15，最低 10");
        clientPreviewMaxRadius = config.getInt("clientPreviewMaxRadius", Configuration.CATEGORY_GENERAL, clientPreviewMaxRadius, 1, Integer.MAX_VALUE, "客户端最大预览半径；实际预览范围取该值与 chainRadius 的较小值，避免大范围预览渲染导致卡顿");
        clientPreviewMaxTargets = config.getInt("clientPreviewMaxTargets", Configuration.CATEGORY_GENERAL, clientPreviewMaxTargets, 1, Integer.MAX_VALUE, "客户端最大预览目标数量；实际预览数量取该值与服务端 chainMaxBlocks 的较小值，避免大范围预览导致卡顿");
        clientPreviewAlphaFadeStartRadius = config.get(Configuration.CATEGORY_GENERAL, "clientPreviewAlphaFadeStartRadius", clientPreviewAlphaFadeStartRadius, "客户端预览透明度开始衰减的距离半径；在此半径内保持最高透明度").getDouble(clientPreviewAlphaFadeStartRadius);
        clientPreviewAlphaFadeEndRadius = config.get(Configuration.CATEGORY_GENERAL, "clientPreviewAlphaFadeEndRadius", clientPreviewAlphaFadeEndRadius, "客户端预览透明度衰减到最低值的距离半径；超过该半径后保持最低透明度").getDouble(clientPreviewAlphaFadeEndRadius);
        clientPreviewAlphaStartValue = config.get(Configuration.CATEGORY_GENERAL, "clientPreviewAlphaStartValue", clientPreviewAlphaStartValue, "客户端预览透明度的起始值；距离不超过衰减起点时使用该透明度").getDouble(clientPreviewAlphaStartValue);
        clientPreviewAlphaEndValue = config.get(Configuration.CATEGORY_GENERAL, "clientPreviewAlphaEndValue", clientPreviewAlphaEndValue, "客户端预览透明度的结束值；距离超过衰减终点时使用该透明度").getDouble(clientPreviewAlphaEndValue);
        clientPreviewAlphaFadeStartRadius = Math.max(0.0D, clientPreviewAlphaFadeStartRadius);
        clientPreviewAlphaFadeEndRadius = Math.max(clientPreviewAlphaFadeStartRadius + 0.001D, clientPreviewAlphaFadeEndRadius);
        clientPreviewAlphaStartValue = Math.max(0.0D, Math.min(1.0D, clientPreviewAlphaStartValue));
        clientPreviewAlphaEndValue = Math.max(0.0D, Math.min(clientPreviewAlphaStartValue, clientPreviewAlphaEndValue));

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
