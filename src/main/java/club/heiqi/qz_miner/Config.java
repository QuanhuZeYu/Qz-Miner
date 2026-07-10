package club.heiqi.qz_miner;

import java.io.File;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.ConfigValueBridge;

/**
 * 运行时配置静态字段门面。
 *
 * <p>权威源为 UILib {@link ConfigManager}（YAML {@code config/qz_miner.yaml}）；
 * 本类不再持有 Forge {@code Configuration}。启动经 {@link #init(File, File)} 回灌静态字段；
 * 保存后由客户端 BATCH_SAVE 监听器再次回灌。</p>
 */
public class Config {

    public static final String CATEGORY_CLIENT = "client";

    /** 权威 YAML 绝对路径（诊断用）；未初始化为空串。 */
    public static String configPath = "";

    public static String greeting = "Hello World";
    public static int chainRadius = 8;
    public static int chainMaxBlocks = 1024;
    public static int chainLoggingShellLayers = 1;
    public static int maxBreakPerTick = 64;
    /**
     * GT 线缆连锁替换单 tick 原子上限：链路目标数超过此值则预校验失败不放行。
     * 安全约束：GT 线缆必须 1 tick 内全部替换完，否则中间态混压导致机器爆炸/线缆烧毁。
     * 默认 1024 与 chainMaxBlocks 对齐；实机后可按主线程 tick 预算调整。
     */
    public static int cableReplaceMaxPerTick = 1024;
    /**
     * 连锁看门狗超时阈值（tick）：玩家连锁 N tick 无真实工作推进则看门狗 publish WatchdogTimeout
     * 协作式回 IDLE（异常兜底，非正常收尾路径）。默认 50 tick ≈ 2.5 秒（B 方案落地后纯做卡死回收速度旋钮，
     * 推进信号已对齐真实工作推进语义，不再为长规划/长执行背锅）。
     */
    public static int chainWatchdogTimeoutTicks = 50;
    public static int parallelTickMinDurationMs = 15;
    public static int parallelTickServerWorkBudgetUnits = 640;
    public static boolean enableUnlimitedOreFortune = false;
    public static boolean enableFortuneForPlacedOre = false;
    public static boolean clientEnablePreviewRender = true;
    public static int parallelTickClientWorkBudgetUnits = 640;
    public static int clientPreviewMaxRadius = 16;
    public static int clientPreviewMaxTargets = 1024;
    public static double clientPreviewAlphaFadeStartRadius = 2.0D;
    public static double clientPreviewAlphaFadeEndRadius = 6.0D;
    public static double clientPreviewAlphaStartValue = 0.78D;
    public static double clientPreviewAlphaEndValue = 0.15D;

    /**
     * 初始化 YAML 配置权威并回灌静态字段。
     *
     * @param configDir  config 目录（通常 {@code <mc>/config}）
     * @param legacyCfg  旧 Forge 建议 cfg 路径，仅作一次性导入源
     */
    public void init(File configDir, File legacyCfg) {
        ConfigManager manager = ConfigBootstrap.bootstrap(configDir, legacyCfg);
        File yaml = ConfigBootstrap.yamlFile();
        configPath = yaml == null ? "" : yaml.getAbsolutePath();
        if (manager == null) {
            MyMod.LOG.error("ConfigBootstrap returned null manager; static defaults remain in effect");
        }
    }

    /**
     * 从当前 Authority 重新回灌静态字段（保存回调 / 测试用）。
     */
    public void load() {
        ConfigManager manager = ConfigBootstrap.manager();
        if (manager == null) {
            return;
        }
        ConfigValueBridge.applyFromAuthority(manager.authority());
    }

    /**
     * 返回当前权威 YAML 路径。
     *
     * @return 绝对路径；未初始化时返回空字符串
     */
    public static String getConfigPath() {
        return configPath == null ? "" : configPath;
    }

    /**
     * 从 Authority 回灌静态字段（替代旧 saveAndReload 中的 load 半段）。
     *
     * <p>写盘已由 {@link ConfigManager#save} 完成；本方法只做内存回灌。</p>
     */
    public static void reloadFromAuthority() {
        MyMod.CONFIG.load();
    }
}
