package club.heiqi.qz_miner.config;

import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;

/** Schema、Config 初值和旧配置导入共用的默认值来源；HUD 默认值直接取类型键声明。 */
public final class QzMinerConfigDefaults {

    public static final String GREETING = "Hello World";
    public static final int CHAIN_RADIUS = 8;
    public static final int CHAIN_MAX_BLOCKS = 1024;
    public static final int CHAIN_LOGGING_SHELL_LAYERS = 1;
    public static final int CABLE_REPLACE_MAX_PER_TICK = 1024;
    public static final int CHAIN_WATCHDOG_TIMEOUT_TICKS = 50;
    public static final int TICK_BUDGET_MS = 15;
    public static final int TICK_BUDGET_MIN_MS = 1;
    public static final int TICK_BUDGET_MAX_MS = 40;
    public static final boolean ENABLE_UNLIMITED_ORE_FORTUNE = false;
    public static final boolean ENABLE_FORTUNE_FOR_PLACED_ORE = false;
    public static final boolean CLIENT_ENABLE_PREVIEW_RENDER = true;
    public static final String CLIENT_TUNNEL_DIRECTION_SOURCE = "look_direction";
    public static final boolean CLIENT_AUTO_TOOL_SWAP_ENABLED = true;
    public static final int CLIENT_PREVIEW_MAX_RADIUS = 16;
    public static final int CLIENT_PREVIEW_MAX_TARGETS = 1024;
    public static final double CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS = 2.0D;
    public static final double CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS = 6.0D;
    public static final double CLIENT_PREVIEW_ALPHA_START_VALUE = 0.78D;
    public static final double CLIENT_PREVIEW_ALPHA_END_VALUE = 0.15D;

    /** 并行执行预算档位（general 段，服务端权威）：deadline = 基线行为。 */
    public static final String PARALLEL_BUDGET_MODE_DEADLINE = "deadline";
    /** 并行执行预算档位：独立分片预算。 */
    public static final String PARALLEL_BUDGET_MODE_SLICE = "slice";
    /** 并行执行预算档位默认（等于基线行为）。 */
    public static final String PARALLEL_BUDGET_MODE = PARALLEL_BUDGET_MODE_DEADLINE;
    /** slice 档每 tick 并行分片预算（毫秒），仅 slice 档生效。 */
    public static final int PARALLEL_SLICE_BUDGET_MS = 4;
    /** 每次成功破坏的净 exhaustion；零维持原值，负值净回补，默认取 Block.harvestBlock 原版常量。 */
    public static final double HARVEST_EXHAUSTION_PER_BLOCK = 0.025D;
    /** 净 exhaustion 合法下界，与原版累加池上界对称；负追加由 HarvestExhaustionSettlement 结算。 */
    public static final double HARVEST_EXHAUSTION_PER_BLOCK_MIN = -40.0D;
    /** 净 exhaustion 合法上界，取 FoodStats.addExhaustion 的累加池上限。 */
    public static final double HARVEST_EXHAUSTION_PER_BLOCK_MAX = 40.0D;
    /** 合法并行预算档位（Schema options 与语义校验共用的稳定顺序）。 */
    public static final java.util.List<String> PARALLEL_BUDGET_MODES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(PARALLEL_BUDGET_MODE_DEADLINE, PARALLEL_BUDGET_MODE_SLICE));

    // ---- 连锁预览观感档位（client 段，配置键位与默认值（真源：QzMinerConfigDefaults））----

    /** 预览渲染后端：auto 能力探测通过用 shader，否则 legacy。 */
    public static final String CLIENT_PREVIEW_RENDER_BACKEND = PreviewRenderBackend.defaultValue().id();
    /** 预览条柱粗细（方块坐标偏移缩放）。 */
    public static final double CLIENT_PREVIEW_BAR_THICKNESS = 0.045D;
    /** 预览颜色来源：builtin 使用内置语义配色。 */
    public static final String CLIENT_PREVIEW_COLOR_SOURCE = PreviewColorSource.defaultValue().id();
    // 六色默认与 ChainPreviewDrawPlan.Visuals.Colors 的 BUILTIN_*_RGB 一致，按模式区分。
    /** CHAIN 大模式默认子模式本地预测颜色（0xRRGGBB）。 */
    public static final int CLIENT_PREVIEW_COLOR_CHAIN = 0x40E6FF;
    /** AREA 大模式默认子模式本地预测颜色（0xRRGGBB；H≈7° 偏红）。 */
    public static final int CLIENT_PREVIEW_COLOR_AREA = 0xE8503C;
    /** INTERACT 大模式默认子模式本地预测颜色（0xRRGGBB；H≈135° 偏绿）。 */
    public static final int CLIENT_PREVIEW_COLOR_INTERACT = 0x58E07A;
    /** 扩展子模式本地预测颜色（0xRRGGBB）。 */
    public static final int CLIENT_PREVIEW_COLOR_SECONDARY = 0xB08CFF;
    /** 远端预测颜色（0xRRGGBB）。 */
    public static final int CLIENT_PREVIEW_COLOR_REMOTE = 0x8FA9D0;
    /** 截断目标颜色（0xRRGGBB；琥珀，避让 AREA 红）。 */
    public static final int CLIENT_PREVIEW_COLOR_TRUNCATED = 0xF8C858;
    /** 深度通道：xray 等于历史行为。 */
    public static final String CLIENT_PREVIEW_DEPTH_MODE = PreviewDepthMode.defaultValue().id();
    /** 预览动画默认档位。 */
    public static final String CLIENT_PREVIEW_ANIMATION = PreviewAnimationMode.defaultValue().id();
    /** 单代动画时长（毫秒）。 */
    public static final int CLIENT_PREVIEW_ANIMATION_DURATION_MS = 120;
    /** 动画相位来源：order 用出现序号。 */
    public static final String CLIENT_PREVIEW_ANIMATION_PHASE = PreviewAnimationPhase.defaultValue().id();
    /** 距离淡出刷新默认档位。 */
    public static final String CLIENT_PREVIEW_FADE_MODE = PreviewFadeMode.defaultValue().id();
    /** signal 档相机位移阈值（格）。 */
    public static final double CLIENT_PREVIEW_FADE_REFRESH_DISTANCE = 0.5D;
    /** signal 档静止时的定期刷新间隔（毫秒）。 */
    public static final int CLIENT_PREVIEW_FADE_FALLBACK_MS = 250;
    /** 条柱屏幕最小宽度（像素）；零使用原世界尺寸投影。 */
    public static final double CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX = 0.0D;
    /** OUTLINE 着色器 pass 的物理像素外扩宽度；零关闭描边，合法域与 shader clamp 一致。 */
    public static final double CLIENT_PREVIEW_OUTLINE_WIDTH_PX = 1.5D;
    /**
     * 两个后端共用面法线亮度系数，颜色烘焙与几何拓扑分别处理。
     * 既有逻辑版本的实机记录见 preview.vert；UILib 5 迁移后的运行路径待实机确认。
     */
    public static final boolean CLIENT_PREVIEW_FACE_SHADING = true;
    /** 预览被上限截断时是否显示提示。 */
    public static final boolean CLIENT_PREVIEW_TRUNCATION_SIGNAL = false;
    /** 预览目标数量硬顶。 */
    public static final int CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP = 4096;
    /** 极端规模 LOD：off 等于历史行为。 */
    public static final String CLIENT_PREVIEW_LOD = PreviewLodMode.defaultValue().id();
    /** LOD alpha 剔除阈值。 */
    public static final double CLIENT_PREVIEW_LOD_MIN_ALPHA = 0.05D;
    /**
     * 同代 appearOrder 归一化后的亮度权重下限，仅作用颜色 rgb。
     * 公式与门控复用 ChainPreviewShaderMath.orderWeight，距离 alpha 独立计算。
     */
    public static final double CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS = 0.55D;
    /**
     * shader 主体 pass 对 AUX_UNDEFINED 内部格线的 rgb 亮度系数。
     * 贯通管面与描边保留原亮度，alpha 使用距离淡出、生长与动画包络。
     * CPU 参考公式见 ChainPreviewShaderMath.interiorDim。
     */
    public static final double CLIENT_PREVIEW_INTERIOR_DIM = 0.65D;
    /** 预览激活时是否取消同目标的原版方块高亮。 */
    public static final boolean CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT = false;
    /** 是否使用版本化预览输入快照及目标去抖。 */
    public static final boolean CLIENT_PREVIEW_VERSIONED_INPUTS = false;
    /** 是否启用统一表现投影覆盖层。 */
    public static final boolean CLIENT_PREVIEW_PRESENTATION_OVERLAY = false;
    /** 是否在 HUD 展示客户端世界采样的执行进度。 */
    public static final boolean CLIENT_PREVIEW_EXECUTION_PROGRESS = false;
    /**
     * 预览后端诊断（HUD 展示当前生效后端与一次性回退原因）缺省值。
     *
     * <p>默认关闭：该行只在排查「shader 档为何不生效 / 走了哪条回退」时需要，
     * 常态显示属于噪声。数据来自渲染线程发布的后端状态快照，关闭时投影侧零采样。</p>
     */
    public static final boolean CLIENT_PREVIEW_BACKEND_DIAGNOSTICS = false;
    /** 远端预览请求超时（毫秒）。 */
    public static final int CLIENT_PREVIEW_REMOTE_TIMEOUT_MS = 5000;

    /** alpha fade 终点相对起点的最小间隔（与历史 load 语义一致）。 */
    public static final double ALPHA_FADE_MIN_SPAN = 0.001D;

    private QzMinerConfigDefaults() {
    }

    /**
     * 将全部 schema 路径的默认 typed 值写入 map（NUMBER 为 Double，BOOLEAN 为 Boolean）。
     *
     * @param target 目标 map
     */
    public static void putAllDefaults(java.util.Map<String, Object> target) {
        target.put("general.greeting", GREETING);
        target.put("general.chainRadius", Double.valueOf(CHAIN_RADIUS));
        target.put("general.chainMaxBlocks", Double.valueOf(CHAIN_MAX_BLOCKS));
        target.put("general.chainLoggingShellLayers", Double.valueOf(CHAIN_LOGGING_SHELL_LAYERS));
        target.put("general.cableReplaceMaxPerTick", Double.valueOf(CABLE_REPLACE_MAX_PER_TICK));
        target.put("general.chainWatchdogTimeoutTicks", Double.valueOf(CHAIN_WATCHDOG_TIMEOUT_TICKS));
        target.put("general.tickBudgetMs", Double.valueOf(TICK_BUDGET_MS));
        target.put("general.enableUnlimitedOreFortune", Boolean.valueOf(ENABLE_UNLIMITED_ORE_FORTUNE));
        target.put("general.enableFortuneForPlacedOre", Boolean.valueOf(ENABLE_FORTUNE_FOR_PLACED_ORE));
        target.put("general.parallelBudgetMode", PARALLEL_BUDGET_MODE);
        target.put("general.parallelSliceBudgetMs", Double.valueOf(PARALLEL_SLICE_BUDGET_MS));
        target.put("general.harvestExhaustionPerBlock",
                Double.valueOf(HARVEST_EXHAUSTION_PER_BLOCK));
        target.put("client.clientEnablePreviewRender", Boolean.valueOf(CLIENT_ENABLE_PREVIEW_RENDER));
        target.put("client.tunnelDirectionSource", CLIENT_TUNNEL_DIRECTION_SOURCE);
        target.put("client.autoToolSwapEnabled", Boolean.valueOf(CLIENT_AUTO_TOOL_SWAP_ENABLED));
        target.put("client.autoToolPrioritySelectors", java.util.Collections.<String>emptyList());
        target.put("client.clientPreviewMaxRadius", Double.valueOf(CLIENT_PREVIEW_MAX_RADIUS));
        target.put("client.clientPreviewMaxTargets", Double.valueOf(CLIENT_PREVIEW_MAX_TARGETS));
        target.put("client.clientPreviewAlphaFadeStartRadius",
                Double.valueOf(CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS));
        target.put("client.clientPreviewAlphaFadeEndRadius",
                Double.valueOf(CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS));
        target.put("client.clientPreviewAlphaStartValue", Double.valueOf(CLIENT_PREVIEW_ALPHA_START_VALUE));
        target.put("client.clientPreviewAlphaEndValue", Double.valueOf(CLIENT_PREVIEW_ALPHA_END_VALUE));
        target.put("client.clientPreviewRenderBackend", CLIENT_PREVIEW_RENDER_BACKEND);
        target.put("client.clientPreviewBarThickness", Double.valueOf(CLIENT_PREVIEW_BAR_THICKNESS));
        target.put("client.clientPreviewColorSource", CLIENT_PREVIEW_COLOR_SOURCE);
        target.put("client.clientPreviewColorChain", Double.valueOf(CLIENT_PREVIEW_COLOR_CHAIN));
        target.put("client.clientPreviewColorArea", Double.valueOf(CLIENT_PREVIEW_COLOR_AREA));
        target.put("client.clientPreviewColorInteract", Double.valueOf(CLIENT_PREVIEW_COLOR_INTERACT));
        target.put("client.clientPreviewColorSecondary", Double.valueOf(CLIENT_PREVIEW_COLOR_SECONDARY));
        target.put("client.clientPreviewColorRemote", Double.valueOf(CLIENT_PREVIEW_COLOR_REMOTE));
        target.put("client.clientPreviewColorTruncated", Double.valueOf(CLIENT_PREVIEW_COLOR_TRUNCATED));
        target.put("client.clientPreviewDepthMode", CLIENT_PREVIEW_DEPTH_MODE);
        target.put("client.clientPreviewAnimation", CLIENT_PREVIEW_ANIMATION);
        target.put("client.clientPreviewAnimationDurationMs", Double.valueOf(CLIENT_PREVIEW_ANIMATION_DURATION_MS));
        target.put("client.clientPreviewAnimationPhase", CLIENT_PREVIEW_ANIMATION_PHASE);
        target.put("client.clientPreviewFadeMode", CLIENT_PREVIEW_FADE_MODE);
        target.put("client.clientPreviewFadeRefreshDistance", Double.valueOf(CLIENT_PREVIEW_FADE_REFRESH_DISTANCE));
        target.put("client.clientPreviewFadeFallbackMs", Double.valueOf(CLIENT_PREVIEW_FADE_FALLBACK_MS));
        target.put("client.clientPreviewMinScreenWidthPx", Double.valueOf(CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX));
        target.put("client.clientPreviewOutlineWidthPx", Double.valueOf(CLIENT_PREVIEW_OUTLINE_WIDTH_PX));
        target.put("client.clientPreviewFaceShading", Boolean.valueOf(CLIENT_PREVIEW_FACE_SHADING));
        target.put("client.clientPreviewTruncationSignal", Boolean.valueOf(CLIENT_PREVIEW_TRUNCATION_SIGNAL));
        target.put("client.clientPreviewMaxTargetsHardCap", Double.valueOf(CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP));
        target.put("client.clientPreviewLod", CLIENT_PREVIEW_LOD);
        target.put("client.clientPreviewLodMinAlpha", Double.valueOf(CLIENT_PREVIEW_LOD_MIN_ALPHA));
        target.put("client.clientPreviewOrderMinBrightness", Double.valueOf(CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS));
        target.put("client.clientPreviewInteriorDim", Double.valueOf(CLIENT_PREVIEW_INTERIOR_DIM));
        target.put("client.clientPreviewSuppressVanillaHighlight",
                Boolean.valueOf(CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT));
        target.put("client.clientPreviewVersionedInputs", Boolean.valueOf(CLIENT_PREVIEW_VERSIONED_INPUTS));
        target.put("client.clientPreviewPresentationOverlay", Boolean.valueOf(CLIENT_PREVIEW_PRESENTATION_OVERLAY));
        target.put("client.clientPreviewExecutionProgress", Boolean.valueOf(CLIENT_PREVIEW_EXECUTION_PROGRESS));
        target.put("client.clientPreviewBackendDiagnostics", Boolean.valueOf(CLIENT_PREVIEW_BACKEND_DIAGNOSTICS));
        target.put("client.clientPreviewRemoteTimeoutMs", Double.valueOf(CLIENT_PREVIEW_REMOTE_TIMEOUT_MS));
        target.put("client.objectGroups", objectGroups());
        // HUD 默认值直接从 schema 类型键投影。
        for (club.heiqi.uilib.ui.config.ConfigKey<?> key : java.util.Arrays.asList(
                QzMinerConfigSchema.HUD_ENABLED, QzMinerConfigSchema.HUD_ANCHOR,
                QzMinerConfigSchema.HUD_OFFSET_X, QzMinerConfigSchema.HUD_OFFSET_Y, QzMinerConfigSchema.HUD_SCALE)) {
            target.put(key.name(), key.defaultValue());
        }
    }

    /**
     * 创建默认红石矿与暮色森林极光方块组，供 schema、文档初始化与恢复默认共用。
     *
     * <p>极光方块（issue #249）：上游 1.7.10 BlockTFAuroraBrick 的 onBlockAdded/onBlockPlaced
     * 以 Math.abs(x + z) % 16 写入位置相位，@* 仅为该 registry 放宽 metadata 匹配。
     * TFBlocks 通过 setBlockName("TFAuroraBrick") 与 registerBlock(block, itemClass, block.getUnlocalizedName())
     * 注册，候选名 TwilightForest:tile.TFAuroraBrick 待实机复核。DEBUG 的
     * stage=PlanStarted read=main-seed seed= 日志输出 registry@meta，入口见
     * ChainPlanningRuntimeFactory.logPlanStarted，文件位置为 logs/fml-client-latest.log。</p>
     *
     * @return 按配置顺序保存的不可变组列表，各组键顺序为 id/modes/members
     */
    public static java.util.List<java.util.Map<String, Object>> objectGroups() {
        java.util.List<java.util.Map<String, Object>> groups =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        // 两组共用稳定模式常量集合，group() 为各组复制列表。
        java.util.List<String> harvestModes = java.util.Arrays.asList(
                ObjectGroupMode.CHAIN_BASE,
                ObjectGroupMode.CHAIN_ORE,
                ObjectGroupMode.AREA_SAME_BLOCK,
                ObjectGroupMode.AREA_ORE);
        groups.add(group(
                "红石矿石",
                harvestModes,
                java.util.Arrays.asList(
                        "minecraft:redstone_ore@*",
                        "minecraft:lit_redstone_ore@*",
                        "etfuturum:deepslate_redstone_ore@*",
                        "etfuturum:deepslate_lit_redstone_ore@*")));
        groups.add(group(
                "暮色森林极光方块",
                harvestModes,
                java.util.Collections.singletonList("TwilightForest:tile.TFAuroraBrick@*")));
        return java.util.Collections.unmodifiableList(groups);
    }

    /**
     * 构造包含明确适用模式的不可变对象组。
     *
     * @param id      组标识（配置数据，不本地化）
     * @param modes   适用模式稳定标识，取自 {@link ObjectGroupMode} 常量
     * @param members 成员 selector（{@code registry@meta} 语法）
     * @return 三键（id/modes/members，保序）不可变组
     */
    private static java.util.Map<String, Object> group(String id, java.util.List<String> modes,
            java.util.List<String> members) {
        java.util.Map<String, Object> group = new java.util.LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("modes", java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(modes)));
        group.put("members", java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(members)));
        return java.util.Collections.unmodifiableMap(group);
    }
}
