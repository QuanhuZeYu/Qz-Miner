package club.heiqi.qz_miner;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    public static final String CATEGORY_CLIENT = "client";

    public static String configPath;
    public static Configuration config;
    public static String greeting = "Hello World";
    public static int chainRadius = 4;
    public static int chainMaxBlocks = 256;
    public static int chainLoggingShellLayers = 1;
    public static int maxBreakPerTick = 16;
    public static int parallelTickMinDurationMs = 15;
    public static boolean enableUnlimitedOreFortune = false;
    public static boolean enableFortuneForPlacedOre = false;
    public static boolean clientEnablePreviewRender = true;
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
        enableUnlimitedOreFortune = config.getBoolean("enableUnlimitedOreFortune", Configuration.CATEGORY_GENERAL, enableUnlimitedOreFortune, "是否解除 GT/BW/GT++ 普通矿的 3 级时运上限；关闭时保持原版逻辑");
        enableFortuneForPlacedOre = config.getBoolean("enableFortuneForPlacedOre", Configuration.CATEGORY_GENERAL, enableFortuneForPlacedOre, "是否允许非自然生成的 GT/BW 矿石也享受时运；关闭时保持原版仅自然矿可时运");
        clientEnablePreviewRender = config.getBoolean("clientEnablePreviewRender", CATEGORY_CLIENT, clientEnablePreviewRender, "是否启用客户端连锁预览计算与渲染；关闭后将不再执行任何预览相关渲染操作");
        clientPreviewMaxRadius = config.getInt("clientPreviewMaxRadius", CATEGORY_CLIENT, clientPreviewMaxRadius, 1, Integer.MAX_VALUE, "客户端最大预览半径；实际预览范围取该值与 chainRadius 的较小值，避免大范围预览渲染导致卡顿");
        clientPreviewMaxTargets = config.getInt("clientPreviewMaxTargets", CATEGORY_CLIENT, clientPreviewMaxTargets, 1, Integer.MAX_VALUE, "客户端最大预览目标数量；实际预览数量取该值与服务端 chainMaxBlocks 的较小值，避免大范围预览导致卡顿");
        clientPreviewAlphaFadeStartRadius = config.get(CATEGORY_CLIENT, "clientPreviewAlphaFadeStartRadius", clientPreviewAlphaFadeStartRadius, "客户端预览透明度开始衰减的距离半径；在此半径内保持最高透明度").getDouble(clientPreviewAlphaFadeStartRadius);
        clientPreviewAlphaFadeEndRadius = config.get(CATEGORY_CLIENT, "clientPreviewAlphaFadeEndRadius", clientPreviewAlphaFadeEndRadius, "客户端预览透明度衰减到最低值的距离半径；超过该半径后保持最低透明度").getDouble(clientPreviewAlphaFadeEndRadius);
        clientPreviewAlphaStartValue = config.get(CATEGORY_CLIENT, "clientPreviewAlphaStartValue", clientPreviewAlphaStartValue, "客户端预览透明度的起始值；距离不超过衰减起点时使用该透明度").getDouble(clientPreviewAlphaStartValue);
        clientPreviewAlphaEndValue = config.get(CATEGORY_CLIENT, "clientPreviewAlphaEndValue", clientPreviewAlphaEndValue, "客户端预览透明度的结束值；距离超过衰减终点时使用该透明度").getDouble(clientPreviewAlphaEndValue);
        clientPreviewAlphaFadeStartRadius = Math.max(0.0D, clientPreviewAlphaFadeStartRadius);
        clientPreviewAlphaFadeEndRadius = Math.max(clientPreviewAlphaFadeStartRadius + 0.001D, clientPreviewAlphaFadeEndRadius);
        clientPreviewAlphaStartValue = Math.max(0.0D, Math.min(1.0D, clientPreviewAlphaStartValue));
        clientPreviewAlphaEndValue = Math.max(0.0D, Math.min(clientPreviewAlphaStartValue, clientPreviewAlphaEndValue));

        if (config.hasChanged()) {
            config.save();
        }
    }

    /**
     * 返回当前配置文件路径。
     *
     * @return 配置文件绝对路径；未初始化时返回空字符串
     */
    public static String getConfigPath() {
        return configPath == null ? "" : configPath;
    }

    /**
     * 保存 Forge 配置并重新加载运行时配置值。
     */
    public static void saveAndReload() {
        if (config == null) {
            return;
        }

        if (config.hasChanged()) {
            config.save();
        }
        MyMod.CONFIG.load();
    }

}
