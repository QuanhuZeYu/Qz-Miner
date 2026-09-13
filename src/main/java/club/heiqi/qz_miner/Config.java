package club.heiqi.qz_miner;

import java.io.File;
import java.util.Collections;
import java.util.List;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.config.ConfigBootstrap;
import club.heiqi.qz_miner.config.PreviewAnimationMode;
import club.heiqi.qz_miner.config.PreviewAnimationPhase;
import club.heiqi.qz_miner.config.PreviewColorSource;
import club.heiqi.qz_miner.config.PreviewDepthMode;
import club.heiqi.qz_miner.config.PreviewFadeMode;
import club.heiqi.qz_miner.config.PreviewLodMode;
import club.heiqi.qz_miner.config.PreviewRenderBackend;
import club.heiqi.qz_miner.config.QzMinerConfigDefaults;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.toolswap.ToolSelector;

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
    /**
     * GT 线缆连锁替换单 tick 原子上限。
     */
    public static int cableReplaceMaxPerTick = QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK;
    /**
     * 连锁看门狗超时阈值（tick）。
     */
    public static int chainWatchdogTimeoutTicks = QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS;
    /** planning、preview 与普通执行共享的单 Tick soft deadline。 */
    public static int tickBudgetMs = QzMinerConfigDefaults.TICK_BUDGET_MS;
    public static boolean enableUnlimitedOreFortune = QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE;
    public static boolean enableFortuneForPlacedOre = QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE;
    /** 并行执行预算档位 id（general 段，服务端权威）：deadline / slice。 */
    public static String parallelBudgetMode = QzMinerConfigDefaults.PARALLEL_BUDGET_MODE;
    /** slice 档每 tick 并行分片预算（毫秒）；deadline 档不生效。 */
    public static int parallelSliceBudgetMs = QzMinerConfigDefaults.PARALLEL_SLICE_BUDGET_MS;
    public static boolean clientEnablePreviewRender = QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER;
    public static TunnelDirectionSource tunnelDirectionSource = TunnelDirectionSource.legacyDefault();
    public static boolean autoToolSwapEnabled = QzMinerConfigDefaults.CLIENT_AUTO_TOOL_SWAP_ENABLED;
    public static List<ToolSelector> autoToolPrioritySelectors = Collections.emptyList();
    public static int clientPreviewMaxRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS;
    public static int clientPreviewMaxTargets = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS;
    public static double clientPreviewAlphaFadeStartRadius =
            QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS;
    public static double clientPreviewAlphaFadeEndRadius =
            QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS;
    public static double clientPreviewAlphaStartValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE;
    public static double clientPreviewAlphaEndValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE;

    // ---- 连锁预览观感档位（client 段；键名/默认值与接口冻结 §E 同源）----
    // 本类只承载「键 → 静态字段 + 缺省兜底 + 分侧发布」（ConfigValueBridge）。
    // 把多个键聚合成不可变观感快照的唯一消费面是 session-core 的
    // ChainPreviewVisualSettings.fromConfig()；渲染/构建路径不得绕过快照直连这些字段。

    /** 预览渲染后端：auto 能力探测通过用 shader、否则 legacy。 */
    public static PreviewRenderBackend clientPreviewRenderBackend = PreviewRenderBackend.defaultValue();
    /** 预览条柱粗细（方块坐标偏移缩放）。 */
    public static double clientPreviewBarThickness = QzMinerConfigDefaults.CLIENT_PREVIEW_BAR_THICKNESS;
    /** 预览颜色来源：builtin 与历史行为逐字节一致。 */
    public static PreviewColorSource clientPreviewColorSource = PreviewColorSource.defaultValue();
    /** 主模式本地预测颜色（0xRRGGBB）。 */
    public static int clientPreviewColorPrimary = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_PRIMARY;
    /** 子模式本地预测颜色（0xRRGGBB）。 */
    public static int clientPreviewColorSecondary = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SECONDARY;
    /** 远端预测颜色（0xRRGGBB）。 */
    public static int clientPreviewColorRemote = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_REMOTE;
    /** 截断目标颜色（0xRRGGBB）。 */
    public static int clientPreviewColorTruncated = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_TRUNCATED;
    /** 深度通道：xray 等于历史行为。 */
    public static PreviewDepthMode clientPreviewDepthMode = PreviewDepthMode.defaultValue();
    /** 预览动画：off / flow / wave；本轮默认 off。 */
    public static PreviewAnimationMode clientPreviewAnimation = PreviewAnimationMode.defaultValue();
    /** 单代动画时长（毫秒）。 */
    public static int clientPreviewAnimationDurationMs = QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_DURATION_MS;
    /** 动画相位来源：order 用出现序号。 */
    public static PreviewAnimationPhase clientPreviewAnimationPhase = PreviewAnimationPhase.defaultValue();
    /** 距离淡出刷新模式：timer / signal / gpu；本轮默认 timer。 */
    public static PreviewFadeMode clientPreviewFadeMode = PreviewFadeMode.defaultValue();
    /** signal 档相机位移阈值（格）。 */
    public static double clientPreviewFadeRefreshDistance =
            QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_REFRESH_DISTANCE;
    /** signal 档无位移时的兜底刷新间隔（毫秒）。 */
    public static int clientPreviewFadeFallbackMs = QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_FALLBACK_MS;
    /** 条柱屏幕最小宽度（像素）；0 表示不钳制；本轮默认 0。 */
    public static double clientPreviewMinScreenWidthPx = QzMinerConfigDefaults.CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX;
    /** 预览被上限截断时是否给出可见提示；本轮默认 false。 */
    public static boolean clientPreviewTruncationSignal = QzMinerConfigDefaults.CLIENT_PREVIEW_TRUNCATION_SIGNAL;
    /** 预览目标数量硬顶。 */
    public static int clientPreviewMaxTargetsHardCap = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP;
    /** 极端规模 LOD：off 等于历史行为。 */
    public static PreviewLodMode clientPreviewLod = PreviewLodMode.defaultValue();
    /** LOD alpha 剔除阈值。 */
    public static double clientPreviewLodMinAlpha = QzMinerConfigDefaults.CLIENT_PREVIEW_LOD_MIN_ALPHA;
    /** 预览激活时是否取消同目标的原版方块高亮。 */
    public static boolean clientPreviewSuppressVanillaHighlight =
            QzMinerConfigDefaults.CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT;
    /** 是否使用版本化预览输入快照（含目标去抖）；本轮默认 false。 */
    public static boolean clientPreviewVersionedInputs = QzMinerConfigDefaults.CLIENT_PREVIEW_VERSIONED_INPUTS;
    /** 是否启用统一表现投影覆盖层。 */
    public static boolean clientPreviewPresentationOverlay = QzMinerConfigDefaults.CLIENT_PREVIEW_PRESENTATION_OVERLAY;
    /** 是否在 HUD 展示执行进度（已执行 / 匹配，B5.2）。 */
    public static boolean clientPreviewExecutionProgress = QzMinerConfigDefaults.CLIENT_PREVIEW_EXECUTION_PROGRESS;
    /** 远端预览请求超时（毫秒）。 */
    public static int clientPreviewRemoteTimeoutMs = QzMinerConfigDefaults.CLIENT_PREVIEW_REMOTE_TIMEOUT_MS;

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
