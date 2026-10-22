package club.heiqi.qz_miner.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import club.heiqi.uilib.ui.config.ConfigCodec;
import club.heiqi.uilib.ui.config.ConfigCodecs;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.config.ConfigSchema;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/** 唯一一组类型键；ConfigDocument 依赖键实例身份，禁止每次启动重建声明。 */
public final class QzMinerConfigSchema {
    private QzMinerConfigSchema() { }

    private static final Map<ConfigKey<?>, List<String>> CHOICES =
            new java.util.LinkedHashMap<ConfigKey<?>, List<String>>();

    private static final ConfigCodec<Map<String, Object>> OBJECT_GROUP_CODEC =
            ConfigCodecs.<Map<String, Object>>object()
                    .requiredField("id", ConfigCodecs.STRING, group -> (String) group.get("id"))
                    .field("modes", ConfigCodecs.STRING_LIST, Collections.<String>emptyList(),
                            group -> group.containsKey("modes") ? stringList(group.get("modes")) : Collections.<String>emptyList())
                    .requiredField("members", ConfigCodecs.STRING_LIST, group -> stringList(group.get("members")))
                    .build(values -> values);

    public static final ConfigKey<String> GREETING =
            new ConfigKey<String>("general.greeting", ConfigCodecs.STRING, QzMinerConfigDefaults.GREETING).withComment("How shall I greet?");

    public static final ConfigKey<Double> CHAIN_RADIUS =
            number("general.chainRadius", QzMinerConfigDefaults.CHAIN_RADIUS, 1, Integer.MAX_VALUE, true).withComment("连锁范围半径（方盒子半径，搜索顺序仍为中心扩散）");

    public static final ConfigKey<Double> CHAIN_MAX_BLOCKS =
            number("general.chainMaxBlocks", QzMinerConfigDefaults.CHAIN_MAX_BLOCKS, 1, Integer.MAX_VALUE, true).withComment("最大连锁数量；超大值（如 >65536）会显著拖慢规划并加剧 abort 频率，建议根据机器性能调整");

    public static final ConfigKey<Double> CHAIN_LOGGING_SHELL_LAYERS =
            number("general.chainLoggingShellLayers", QzMinerConfigDefaults.CHAIN_LOGGING_SHELL_LAYERS, 1, Integer.MAX_VALUE, true).withComment("CHAIN 伐木子模式每次向外扩展的壳层数；1 表示围绕当前原木检查一圈 3x3x3 邻域");

    public static final ConfigKey<Double> CABLE_REPLACE_MAX_PER_TICK =
            number("general.cableReplaceMaxPerTick", QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK, 1, Integer.MAX_VALUE, true).withComment("GT 线缆连锁替换单 tick 原子上限；超过此值的链路预校验失败不放行（防电压不匹配爆炸）；默认 1024");

    public static final ConfigKey<Double> CHAIN_WATCHDOG_TIMEOUT_TICKS =
            number("general.chainWatchdogTimeoutTicks", QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS, 20, Integer.MAX_VALUE, true).withComment("连锁看门狗超时阈值（tick）：玩家连锁 N tick 无真实工作推进则协作式回 IDLE（异常兜底，默认 50 ≈ 2.5 秒）");

    public static final ConfigKey<Double> TICK_BUDGET_MS =
            number("general.tickBudgetMs", QzMinerConfigDefaults.TICK_BUDGET_MS, QzMinerConfigDefaults.TICK_BUDGET_MIN_MS, QzMinerConfigDefaults.TICK_BUDGET_MAX_MS, true).withComment("planning、preview 与普通执行共享的每 Tick soft deadline（毫秒）；默认 15，可配置 1..40");

    public static final ConfigKey<Boolean> ENABLE_UNLIMITED_ORE_FORTUNE =
            new ConfigKey<Boolean>("general.enableUnlimitedOreFortune", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE)).withComment("是否解除 GT/BW/GT++ 普通矿的 3 级时运上限；关闭时保持原版逻辑");

    public static final ConfigKey<Boolean> ENABLE_FORTUNE_FOR_PLACED_ORE =
            new ConfigKey<Boolean>("general.enableFortuneForPlacedOre", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE)).withComment("是否允许非自然生成的 GT/BW 矿石也享受时运；关闭时保持原版仅自然矿可时运");

    public static final ConfigKey<String> PARALLEL_BUDGET_MODE =
            choice("general.parallelBudgetMode", QzMinerConfigDefaults.PARALLEL_BUDGET_MODE, QzMinerConfigDefaults.PARALLEL_BUDGET_MODES.toArray(new String[0])).withComment("并行执行预算档位：deadline 沿用 tickBudgetMs 共享 deadline（默认，等于现状）；"
                                + "slice 改为每 tick 独立分片预算，主线程不再等满 deadline");

    public static final ConfigKey<Double> PARALLEL_SLICE_BUDGET_MS =
            number("general.parallelSliceBudgetMs", QzMinerConfigDefaults.PARALLEL_SLICE_BUDGET_MS, 1, 40, true).withComment("slice 档每 tick 的并行分片预算（毫秒），合法 1..40；deadline 档不生效");

    public static final ConfigKey<Double> HARVEST_EXHAUSTION_PER_BLOCK =
            number("general.harvestExhaustionPerBlock", QzMinerConfigDefaults.HARVEST_EXHAUSTION_PER_BLOCK, QzMinerConfigDefaults.HARVEST_EXHAUSTION_PER_BLOCK_MIN,
                                QzMinerConfigDefaults.HARVEST_EXHAUSTION_PER_BLOCK_MAX, false).withComment("连锁/爆破每成功破坏一个方块消耗的饥饿值（原版 exhaustion 单位），"
                                + "按实测增量覆盖成该值：0.025 等于原版每方块消耗（默认，逐值等于现状），"
                                + "0 表示完全不消耗，负值表示净回补；"
                                + "合法 -40..40，40 等于原版 exhaustion 累加上限、-40 与之对称。"
                                + "覆写 harvestBlock 的方块同样生效；exhaustion 接近 40 上限时会被截断");

    public static final ConfigKey<Boolean> CLIENT_ENABLE_PREVIEW_RENDER =
            new ConfigKey<Boolean>("client.clientEnablePreviewRender", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER)).withComment("是否启用客户端连锁预览计算与渲染；关闭后将不再执行任何预览相关渲染操作");

    public static final ConfigKey<String> TUNNEL_DIRECTION_SOURCE =
            choice("client.tunnelDirectionSource", QzMinerConfigDefaults.CLIENT_TUNNEL_DIRECTION_SOURCE, TunnelDirectionSource.ids()).withComment("look_direction 按视线主轴；hit_face 按命中面向方块内部");

    public static final ConfigKey<Boolean> AUTO_TOOL_SWAP_ENABLED =
            new ConfigKey<Boolean>("client.autoToolSwapEnabled", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_AUTO_TOOL_SWAP_ENABLED)).withComment("是否启用自动工具换位；关闭时安全还原已有换位");

    public static final ConfigKey<List<String>> AUTO_TOOL_PRIORITY_SELECTORS =
            new ConfigKey<List<String>>("client.autoToolPrioritySelectors", ConfigCodecs.STRING_LIST, Collections.<String>emptyList()).withComment("有序工具选择器：namespace:path@subtype、namespace:path@* 或 ore:name");

    public static final ConfigKey<Double> CLIENT_PREVIEW_MAX_RADIUS =
            number("client.clientPreviewMaxRadius", QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS, 1, Integer.MAX_VALUE, true).withComment("客户端最大预览半径；实际预览范围取该值与 chainRadius 的较小值");

    public static final ConfigKey<Double> CLIENT_PREVIEW_MAX_TARGETS =
            number("client.clientPreviewMaxTargets", QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS, 1, Integer.MAX_VALUE, true).withComment("客户端最大预览目标数量；实际预览数量取该值与服务端 chainMaxBlocks 的较小值");

    public static final ConfigKey<Double> CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS =
            number("client.clientPreviewAlphaFadeStartRadius", QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS, 0, Double.MAX_VALUE, false).withComment("客户端预览透明度开始衰减的距离半径");

    public static final ConfigKey<Double> CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS =
            number("client.clientPreviewAlphaFadeEndRadius", QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS, 0, Double.MAX_VALUE, false).withComment("客户端预览透明度衰减到最低值的距离半径");

    public static final ConfigKey<Double> CLIENT_PREVIEW_ALPHA_START_VALUE =
            number("client.clientPreviewAlphaStartValue", QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE, 0, 1, false).withComment("客户端预览透明度的起始值");

    public static final ConfigKey<Double> CLIENT_PREVIEW_ALPHA_END_VALUE =
            number("client.clientPreviewAlphaEndValue", QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE, 0, 1, false).withComment("客户端预览透明度的结束值");

    public static final ConfigKey<String> CLIENT_PREVIEW_RENDER_BACKEND =
            choice("client.clientPreviewRenderBackend", QzMinerConfigDefaults.CLIENT_PREVIEW_RENDER_BACKEND, PreviewRenderBackend.ids()).withComment("预览渲染后端：auto 能力探测通过用 shader、否则 legacy（默认）；"
                                + "shader 档探测失败仍回退 legacy 并记录诊断；legacy 恒为固定管线");

    public static final ConfigKey<Double> CLIENT_PREVIEW_BAR_THICKNESS =
            number("client.clientPreviewBarThickness", QzMinerConfigDefaults.CLIENT_PREVIEW_BAR_THICKNESS, 0.005, 0.2, false).withComment("预览条柱粗细（方块坐标偏移缩放），默认 0.045，合法 0.005..0.2");

    public static final ConfigKey<String> CLIENT_PREVIEW_COLOR_SOURCE =
            choice("client.clientPreviewColorSource", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SOURCE, PreviewColorSource.ids()).withComment("预览颜色来源：builtin 内置六色（默认，按大模式区分）；"
                                + "config 使用下方六个 RGB 键的语义配色");

    public static final ConfigKey<Double> CLIENT_PREVIEW_COLOR_CHAIN =
            number("client.clientPreviewColorChain", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_CHAIN, 0, 0xFFFFFF, true).withComment("CHAIN 大模式（默认子模式）本地预测颜色（0xRRGGBB），默认 0x40E6FF");

    public static final ConfigKey<Double> CLIENT_PREVIEW_COLOR_AREA =
            number("client.clientPreviewColorArea", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_AREA, 0, 0xFFFFFF, true).withComment("AREA 大模式（默认子模式）本地预测颜色（0xRRGGBB），默认 0xE8503C");

    public static final ConfigKey<Double> CLIENT_PREVIEW_COLOR_INTERACT =
            number("client.clientPreviewColorInteract", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_INTERACT, 0, 0xFFFFFF, true).withComment("INTERACT 大模式（默认子模式）本地预测颜色（0xRRGGBB），默认 0x58E07A");

    public static final ConfigKey<Double> CLIENT_PREVIEW_COLOR_SECONDARY =
            number("client.clientPreviewColorSecondary", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SECONDARY, 0, 0xFFFFFF, true).withComment("扩展子模式本地预测颜色（0xRRGGBB），默认 0xB08CFF");

    public static final ConfigKey<Double> CLIENT_PREVIEW_COLOR_REMOTE =
            number("client.clientPreviewColorRemote", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_REMOTE, 0, 0xFFFFFF, true).withComment("远端预测颜色（0xRRGGBB），默认 0x8FA9D0");

    public static final ConfigKey<Double> CLIENT_PREVIEW_COLOR_TRUNCATED =
            number("client.clientPreviewColorTruncated", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_TRUNCATED, 0, 0xFFFFFF, true).withComment("截断目标颜色（0xRRGGBB），默认 0xF8C858");

    public static final ConfigKey<String> CLIENT_PREVIEW_DEPTH_MODE =
            choice("client.clientPreviewDepthMode", QzMinerConfigDefaults.CLIENT_PREVIEW_DEPTH_MODE, PreviewDepthMode.ids()).withComment("深度通道：xray 恒可见（默认，等于现状）；occlude 参与深度测试；"
                                + "outline 主体遮挡 + 置顶轮廓");

    public static final ConfigKey<String> CLIENT_PREVIEW_ANIMATION =
            choice("client.clientPreviewAnimation", QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION, PreviewAnimationMode.ids()).withComment("预览动画：off 关闭（默认，等于本轮基线）；flow 整体流动；"
                                + "wave 按出现顺序逐波生长（接线属下一批）");

    public static final ConfigKey<Double> CLIENT_PREVIEW_ANIMATION_DURATION_MS =
            number("client.clientPreviewAnimationDurationMs", QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_DURATION_MS, 0, 2000, true).withComment("单代动画时长（毫秒），0 表示瞬时完成，合法 0..2000");

    public static final ConfigKey<String> CLIENT_PREVIEW_ANIMATION_PHASE =
            choice("client.clientPreviewAnimationPhase", QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_PHASE, PreviewAnimationPhase.ids()).withComment("动画相位来源：order 用出现序号（默认）；hash 用坐标 + 代次稳定哈希"
                                + "（预留档：本轮未接线，改此键观感不变）");

    public static final ConfigKey<String> CLIENT_PREVIEW_FADE_MODE =
            choice("client.clientPreviewFadeMode", QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_MODE, PreviewFadeMode.ids()).withComment("距离淡出刷新：timer 1 Hz 兜底（默认，等于现状）；"
                                + "signal 相机变更信号驱动（接线属下一批）；gpu 由着色器逐帧计算");

    public static final ConfigKey<Double> CLIENT_PREVIEW_FADE_REFRESH_DISTANCE =
            number("client.clientPreviewFadeRefreshDistance", QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_REFRESH_DISTANCE, 0.0, 8.0, false).withComment("signal 档相机位移阈值（格）：位移达到该值才提升 visual revision，合法 0..8");

    public static final ConfigKey<Double> CLIENT_PREVIEW_FADE_FALLBACK_MS =
            number("client.clientPreviewFadeFallbackMs", QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_FALLBACK_MS, 50, 5000, true).withComment("signal 档无位移时的兜底刷新间隔（毫秒），合法 50..5000");

    public static final ConfigKey<Double> CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX =
            number("client.clientPreviewMinScreenWidthPx", QzMinerConfigDefaults.CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX, 0.0, 8.0, false).withComment("条柱在屏幕上的最小宽度（像素），0 表示不钳制（默认，等于现状）、1..8 生效，"
                                + "合法 0..8；着色器后端按该值单向钳制条柱屏幕宽度");

    public static final ConfigKey<Double> CLIENT_PREVIEW_OUTLINE_WIDTH_PX =
            number("client.clientPreviewOutlineWidthPx", QzMinerConfigDefaults.CLIENT_PREVIEW_OUTLINE_WIDTH_PX, 0.0, 8.0, false).withComment("描边壳沿面方向的外扩宽度（物理像素），默认 1.5（等于接线前写死值，观感不变）、"
                                + "0 表示关闭描边，合法 0..8；仅着色器后端的 outline 深度档消费");

    public static final ConfigKey<Boolean> CLIENT_PREVIEW_FACE_SHADING =
            new ConfigKey<Boolean>("client.clientPreviewFaceShading", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_FACE_SHADING)).withComment("是否按面朝向烘焙明暗（顶面最亮、底面最暗），默认 false（等于接线前观感）；"
                                + "开启后 legacy 颜色流与着色器顶点色使用同一张亮度表");

    public static final ConfigKey<Boolean> CLIENT_PREVIEW_TRUNCATION_SIGNAL =
            new ConfigKey<Boolean>("client.clientPreviewTruncationSignal", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_TRUNCATION_SIGNAL)).withComment("预览被目标上限截断时是否给出可见提示（默认 false；HUD 文案接线属下一批）");

    public static final ConfigKey<Double> CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP =
            number("client.clientPreviewMaxTargetsHardCap", QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP, 1, 4096, true).withComment("预览目标数量硬顶，合法 1..4096；实际取该值与 clientPreviewMaxTargets、"
                                + "服务端 chainMaxBlocks 的较小值。配额按唯一坐标去重后占用，"
                                + "HUD 的预览已匹配仍是含重复的原始读取数");

    public static final ConfigKey<String> CLIENT_PREVIEW_LOD =
            choice("client.clientPreviewLod", QzMinerConfigDefaults.CLIENT_PREVIEW_LOD, PreviewLodMode.ids()).withComment("极端规模 LOD：off 关闭（默认，等于历史行为）；auto 按距离合并并做 alpha 剔除");

    public static final ConfigKey<Double> CLIENT_PREVIEW_LOD_MIN_ALPHA =
            number("client.clientPreviewLodMinAlpha", QzMinerConfigDefaults.CLIENT_PREVIEW_LOD_MIN_ALPHA, 0.0, 1.0, false).withComment("LOD alpha 剔除阈值：低于该值的条柱不参与构建，合法 0..1");

    public static final ConfigKey<Double> CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS =
            number("client.clientPreviewOrderMinBrightness", QzMinerConfigDefaults.CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS, 0.0, 1.0, false).withComment("连锁序亮度权重下限：预览集合里序号最大（离瞄准起点最远）的条柱乘的亮度权重，"
                                + "合法 0..1；1.0 关闭本能力（等于现状），默认 0.55 时起点 1.0、最远 0.55；"
                                + "只乘颜色亮度，不影响由距离淡出决定的 alpha");

    public static final ConfigKey<Double> CLIENT_PREVIEW_INTERIOR_DIM =
            number("client.clientPreviewInteriorDim", QzMinerConfigDefaults.CLIENT_PREVIEW_INTERIOR_DIM, 0.0, 1.0, false).withComment("内部结构亮度系数：预览里内部格线（junction 补块 / 共享顶点，无贯通面槽位的顶点）"
                                + "乘的颜色亮度，合法 0..1；1.0 关闭本能力（等于现状），默认 0.65 时内部格线"
                                + "为原亮度的 65%，外轮廓（贯通管面）保持 1.0；只压暗颜色亮度，不影响 alpha，"
                                + "也不作用于描边 pass（描边色即外轮廓）");

    public static final ConfigKey<Boolean> CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT =
            new ConfigKey<Boolean>("client.clientPreviewSuppressVanillaHighlight", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT)).withComment("预览激活且瞄准同一目标时取消原版方块高亮，避免双重指示");

    public static final ConfigKey<Boolean> CLIENT_PREVIEW_VERSIONED_INPUTS =
            new ConfigKey<Boolean>("client.clientPreviewVersionedInputs", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_VERSIONED_INPUTS)).withComment("是否使用版本化预览输入快照（含目标去抖），默认 false（接线属下一批）；"
                                + "关闭时走逐字段比较");

    public static final ConfigKey<Boolean> CLIENT_PREVIEW_PRESENTATION_OVERLAY =
            new ConfigKey<Boolean>("client.clientPreviewPresentationOverlay", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_PRESENTATION_OVERLAY)).withComment("是否启用统一表现投影覆盖层（HUD 截断 / 进度 / 远端失败的单一事实源）");

    public static final ConfigKey<Boolean> CLIENT_PREVIEW_EXECUTION_PROGRESS =
            new ConfigKey<Boolean>("client.clientPreviewExecutionProgress", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_EXECUTION_PROGRESS)).withComment("是否在 HUD 展示执行进度（已执行 / 匹配）；进度由客户端世界采样得到，默认关闭");

    public static final ConfigKey<Boolean> CLIENT_PREVIEW_BACKEND_DIAGNOSTICS =
            new ConfigKey<Boolean>("client.clientPreviewBackendDiagnostics", ConfigCodecs.BOOLEAN, Boolean.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_BACKEND_DIAGNOSTICS)).withComment("是否在 HUD 展示预览后端诊断（当前生效后端与一次性回退原因）；默认关闭");

    public static final ConfigKey<Double> CLIENT_PREVIEW_REMOTE_TIMEOUT_MS =
            number("client.clientPreviewRemoteTimeoutMs", QzMinerConfigDefaults.CLIENT_PREVIEW_REMOTE_TIMEOUT_MS, 250, 60000, true).withComment("远端预览请求超时（毫秒），合法 250..60000；超时丢弃陈旧响应并清预览激活");

    public static final ConfigKey<List<Map<String, Object>>> OBJECT_GROUPS =
            new ConfigKey<List<Map<String, Object>>>("client.objectGroups", ConfigCodecs.listOf(OBJECT_GROUP_CODEC), QzMinerConfigDefaults.objectGroups()).withComment("按组标识和适用模式组织连锁挖掘对象");

    public static final ConfigKey<Boolean> HUD_ENABLED =
            new ConfigKey<Boolean>("client.hudEnabled", ConfigCodecs.BOOLEAN, true).withComment("显示 Miner 状态 HUD");
    public static final ConfigKey<String> HUD_ANCHOR =
            choice("client.hudAnchor", "TOP_LEFT", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
                    "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT")
                    .withComment("HUD 相对屏幕的锚点");
    public static final ConfigKey<Double> HUD_OFFSET_X =
            number("client.hudOffsetX", 12, -16384, 16384, false).withComment("HUD 水平逻辑偏移");
    public static final ConfigKey<Double> HUD_OFFSET_Y =
            number("client.hudOffsetY", 12, -16384, 16384, false).withComment("HUD 垂直逻辑偏移");
    public static final ConfigKey<Double> HUD_SCALE =
            number("client.hudScale", 1, 0.5, 3, false).withComment("HUD 内容缩放");

    public static final ConfigSchema SCHEMA = ConfigSchema.of(
            GREETING,
            CHAIN_RADIUS,
            CHAIN_MAX_BLOCKS,
            CHAIN_LOGGING_SHELL_LAYERS,
            CABLE_REPLACE_MAX_PER_TICK,
            CHAIN_WATCHDOG_TIMEOUT_TICKS,
            TICK_BUDGET_MS,
            ENABLE_UNLIMITED_ORE_FORTUNE,
            ENABLE_FORTUNE_FOR_PLACED_ORE,
            PARALLEL_BUDGET_MODE,
            PARALLEL_SLICE_BUDGET_MS,
            HARVEST_EXHAUSTION_PER_BLOCK,
            CLIENT_ENABLE_PREVIEW_RENDER,
            TUNNEL_DIRECTION_SOURCE,
            AUTO_TOOL_SWAP_ENABLED,
            AUTO_TOOL_PRIORITY_SELECTORS,
            CLIENT_PREVIEW_MAX_RADIUS,
            CLIENT_PREVIEW_MAX_TARGETS,
            CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS,
            CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS,
            CLIENT_PREVIEW_ALPHA_START_VALUE,
            CLIENT_PREVIEW_ALPHA_END_VALUE,
            CLIENT_PREVIEW_RENDER_BACKEND,
            CLIENT_PREVIEW_BAR_THICKNESS,
            CLIENT_PREVIEW_COLOR_SOURCE,
            CLIENT_PREVIEW_COLOR_CHAIN,
            CLIENT_PREVIEW_COLOR_AREA,
            CLIENT_PREVIEW_COLOR_INTERACT,
            CLIENT_PREVIEW_COLOR_SECONDARY,
            CLIENT_PREVIEW_COLOR_REMOTE,
            CLIENT_PREVIEW_COLOR_TRUNCATED,
            CLIENT_PREVIEW_DEPTH_MODE,
            CLIENT_PREVIEW_ANIMATION,
            CLIENT_PREVIEW_ANIMATION_DURATION_MS,
            CLIENT_PREVIEW_ANIMATION_PHASE,
            CLIENT_PREVIEW_FADE_MODE,
            CLIENT_PREVIEW_FADE_REFRESH_DISTANCE,
            CLIENT_PREVIEW_FADE_FALLBACK_MS,
            CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX,
            CLIENT_PREVIEW_OUTLINE_WIDTH_PX,
            CLIENT_PREVIEW_FACE_SHADING,
            CLIENT_PREVIEW_TRUNCATION_SIGNAL,
            CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP,
            CLIENT_PREVIEW_LOD,
            CLIENT_PREVIEW_LOD_MIN_ALPHA,
            CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS,
            CLIENT_PREVIEW_INTERIOR_DIM,
            CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT,
            CLIENT_PREVIEW_VERSIONED_INPUTS,
            CLIENT_PREVIEW_PRESENTATION_OVERLAY,
            CLIENT_PREVIEW_EXECUTION_PROGRESS,
            CLIENT_PREVIEW_BACKEND_DIAGNOSTICS,
            CLIENT_PREVIEW_REMOTE_TIMEOUT_MS,
            OBJECT_GROUPS, HUD_ENABLED, HUD_ANCHOR, HUD_OFFSET_X, HUD_OFFSET_Y, HUD_SCALE);

    /** 命令与显式旧配置导入共用路径解析，返回的始终是已声明实例。 */
    public static ConfigKey<?> key(String path) {
        for (ConfigKey<?> key : SCHEMA.keys()) {
            if (key.name().equals(path)) return key;
        }
        throw new IllegalArgumentException("未知配置路径: " + path);
    }

    private static ConfigKey<Double> number(String path, double value, double min, double max, boolean integer) {
        // 旧 number 写成 32.0；INTEGER codec 会拒绝该 YAML，所以保留 DOUBLE 并精确检查整数。
        return new ConfigKey<Double>(path, ConfigCodecs.DOUBLE, value,
                number -> number >= min && number <= max && (!integer || number == Math.rint(number)));
    }

    /** 页面从同一声明读取选择域，不另维护容易漂移的选项表。 */
    public static List<String> choices(ConfigKey<?> key) {
        List<String> values = CHOICES.get(key);
        return values == null ? Collections.<String>emptyList() : values;
    }

    private static ConfigKey<String> choice(String path, String value, String... options) {
        List<String> values = Collections.unmodifiableList(Arrays.asList(options.clone()));
        ConfigKey<String> key = new ConfigKey<String>(path, ConfigCodecs.STRING, value, values::contains);
        CHOICES.put(key, values);
        return key;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        return (List<String>) value;
    }
}
