package club.heiqi.qz_miner;

import java.io.File;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.QzMinerConfigDefaults;
import club.heiqi.qz_miner.autotool.AutoToolSelectionConfig;

/**
 * 运行时配置静态字段门面。
 *
 * <p>权威源为 UILib {@link ConfigManager}（YAML {@code config/qz_miner.yaml}）。
 * 静态字段初值来自 {@link QzMinerConfigDefaults}；启动经 {@link #init} 全量回灌；
 * 运行时 BATCH_SAVE/RELOAD 分侧发布（client 主线程 / general 主线程），见 ClientConfigChangeListener。</p>
 */
public class Config {

    public static final String CATEGORY_CLIENT = "client";

    /** 权威 YAML 绝对路径（诊断用）；未初始化为空串。 */
    public static String configPath = "";

    public static String greeting = QzMinerConfigDefaults.GREETING;
    public static int chainRadius = QzMinerConfigDefaults.CHAIN_RADIUS;
    public static int chainMaxBlocks = QzMinerConfigDefaults.CHAIN_MAX_BLOCKS;
    public static int chainLoggingShellLayers = QzMinerConfigDefaults.CHAIN_LOGGING_SHELL_LAYERS;
    public static int maxBreakPerTick = QzMinerConfigDefaults.MAX_BREAK_PER_TICK;
    /**
     * GT 线缆连锁替换单 tick 原子上限。
     */
    public static int cableReplaceMaxPerTick = QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK;
    /**
     * 连锁看门狗超时阈值（tick）。
     */
    public static int chainWatchdogTimeoutTicks = QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS;
    public static int parallelTickMinDurationMs = QzMinerConfigDefaults.PARALLEL_TICK_MIN_DURATION_MS;
    public static int parallelTickServerWorkBudgetUnits =
            QzMinerConfigDefaults.PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS;
    public static boolean enableUnlimitedOreFortune = QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE;
    public static boolean enableFortuneForPlacedOre = QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE;
    public static AutoToolSelectionConfig autoToolSelection = QzMinerConfigDefaults.autoToolSelectionConfig();
    public static boolean clientEnablePreviewRender = QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER;
    public static int parallelTickClientWorkBudgetUnits =
            QzMinerConfigDefaults.PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS;
    public static int clientPreviewMaxRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS;
    public static int clientPreviewMaxTargets = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS;
    public static double clientPreviewAlphaFadeStartRadius =
            QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS;
    public static double clientPreviewAlphaFadeEndRadius =
            QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS;
    public static double clientPreviewAlphaStartValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE;
    public static double clientPreviewAlphaEndValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE;

    /**
     * 初始化 YAML 配置权威并全量回灌静态字段（preInit，运行前）。
     *
     * @param configDir config 目录
     * @param legacyCfg 旧 Forge cfg，仅一次性导入源
     */
    public void init(File configDir, File legacyCfg) {
        ConfigManager manager = ConfigBootstrap.bootstrap(configDir, legacyCfg);
        File yaml = ConfigBootstrap.yamlFile();
        configPath = yaml == null ? "" : yaml.getAbsolutePath();
        if (manager == null) {
            throw new IllegalStateException("ConfigBootstrap returned null manager");
        }
    }

    /**
     * @return 权威 YAML 路径；未初始化时返回空字符串
     */
    public static String getConfigPath() {
        return configPath == null ? "" : configPath;
    }

}
