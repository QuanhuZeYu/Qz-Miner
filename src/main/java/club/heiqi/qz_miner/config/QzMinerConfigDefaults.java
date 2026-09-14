package club.heiqi.qz_miner.config;

import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;

/**
 * 全部历史配置默认值的单一来源。
 *
 * <p>Schema 默认、{@code Config} 静态初值、LegacyCfgImporter Property getter 默认必须引用本类，
 * 避免三处漂移（尤其 clientEnablePreviewRender=true 与 alpha/fade 非零默认）。</p>
 */
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
    /** 合法并行预算档位（Schema options 与语义校验共用的稳定顺序）。 */
    public static final java.util.List<String> PARALLEL_BUDGET_MODES = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList(PARALLEL_BUDGET_MODE_DEADLINE, PARALLEL_BUDGET_MODE_SLICE));

    // ---- 连锁预览观感档位（client 段，配置键位与默认值（真源：QzMinerConfigDefaults））----

    /** 预览渲染后端：auto 能力探测通过用 shader，否则 legacy。 */
    public static final String CLIENT_PREVIEW_RENDER_BACKEND = PreviewRenderBackend.defaultValue().id();
    /** 预览条柱粗细（方块坐标偏移缩放）。 */
    public static final double CLIENT_PREVIEW_BAR_THICKNESS = 0.045D;
    /** 预览颜色来源：builtin 与历史行为逐字节一致。 */
    public static final String CLIENT_PREVIEW_COLOR_SOURCE = PreviewColorSource.defaultValue().id();
    // 六色语义默认（0xRRGGBB）：与 ChainPreviewDrawPlan.Visuals.Colors 的 BUILTIN_*_RGB 同值——
    // 「builtin 档」与「config 档但未改过颜色」因此给出同一套可区分配色（用户裁定 B 档：
    // 默认颜色按大模式区分）。CHAIN 保持历史主色 0x40E6FF，使默认大模式观感与接线前一致。
    // 用户裁定：AREA（爆破模式）色相明确偏红、INTERACT（范围交互）色相明确偏绿；
    // 三槽连带调整的取舍与可辨识度验算见 ChainPreviewDrawPlan.Visuals.Colors 的同名常量。
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
    /** 预览动画：本轮默认 off（逐波生长接线属下一批 B3.1/B3.3）。 */
    public static final String CLIENT_PREVIEW_ANIMATION = PreviewAnimationMode.defaultValue().id();
    /** 单代动画时长（毫秒）。 */
    public static final int CLIENT_PREVIEW_ANIMATION_DURATION_MS = 120;
    /** 动画相位来源：order 用出现序号。 */
    public static final String CLIENT_PREVIEW_ANIMATION_PHASE = PreviewAnimationPhase.defaultValue().id();
    /** 距离淡出刷新模式：本轮默认 timer（signal 接线属下一批 B1.3）。 */
    public static final String CLIENT_PREVIEW_FADE_MODE = PreviewFadeMode.defaultValue().id();
    /** signal 档相机位移阈值（格）。 */
    public static final double CLIENT_PREVIEW_FADE_REFRESH_DISTANCE = 0.5D;
    /** signal 档无位移时的兜底刷新间隔（毫秒）。 */
    public static final int CLIENT_PREVIEW_FADE_FALLBACK_MS = 250;
    /** 条柱屏幕最小宽度（像素）；0 表示不钳制（默认；已接线：settings → DrawPlan → uMinScreenWidthPx）。 */
    public static final double CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX = 0.0D;
    /**
     * 描边壳外扩宽度（物理像素）；默认 1.5D（等于接线前渲染器内写死的 {@code OUTLINE_WIDTH_DEFAULT_PX}，
     * 观感不变），0 表示关闭描边。
     *
     * <p>仅着色器后端的 OUTLINE 深度档消费（legacy 固定管线无屏幕空间描边能力），
     * 取值区间与着色器内 {@code clamp(uOutlineWidthPx, 0.0, 8.0)} 一致。</p>
     */
    public static final double CLIENT_PREVIEW_OUTLINE_WIDTH_PX = 1.5D;
    /**
     * 面朝向烘焙明暗（view-independent face shading）；默认 true。
     *
     * <p>两个后端使用同一张「面法线 → 亮度系数」表，都是「字面常量 × 同一 palette 常量」的
     * 单次 IEEE 单精度乘法 ⇒ 逐位一致。它只乘一个亮度系数：不动几何、不动拓扑、不改可见集合，
     * 因此被选为唯一上调的观感默认（用户裁定 2026-09-14：默认档需要体积感）。</p>
     *
     * <p><b>与其它默认值的性质差异</b>：本项曾默认 false（"逐字节等于接线前观感"），
     * 上调属**观感变更**而非等价接线——查询"默认值是否等于历史行为"时本项是例外。</p>
     *
     * <p><b>实机验证状态</b>：面明暗路径此前只在关闭态下过真机（preview.vert「实机验证记录」
     * 两条都是"面朝向明暗关"）。默认开启后必须真机确认一次「无异常且观感可接受」，
     * 确认后才在 preview.vert 追加标记行。</p>
     */
    public static final boolean CLIENT_PREVIEW_FACE_SHADING = true;
    /** 预览被上限截断时是否给出可见提示（本轮默认 false；展示接线属下一批 B1.1）。 */
    public static final boolean CLIENT_PREVIEW_TRUNCATION_SIGNAL = false;
    /** 预览目标数量硬顶。 */
    public static final int CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP = 4096;
    /** 极端规模 LOD：off 等于历史行为。 */
    public static final String CLIENT_PREVIEW_LOD = PreviewLodMode.defaultValue().id();
    /** LOD alpha 剔除阈值。 */
    public static final double CLIENT_PREVIEW_LOD_MIN_ALPHA = 0.05D;
    /**
     * 连锁序（appearOrder）**亮度**权重下限：预览集合里序号最大（离瞄准起点最远）处乘的权重。
     *
     * <p><b>作用位置是颜色亮度</b>（{@code color = color * orderWeight}），<b>不乘 alpha</b>——
     * 距离淡出与连锁序因此是两个独立维度、各自权威（用户裁定：乘在同一个量上会让两个配置的语义
     * 互相污染，且远距 alpha 本已很小、再乘权重落进视觉噪声）。{@code 1.0} = 关闭本能力
     * （逐值等于现状），{@code 0.55}（默认）= 起点 1.0、最远 0.55。
     * 归一化分母是「同代目标总数」{@code uAppearSpan}；公式、门控与「未定义序号恒 1.0」的判据见
     * {@code chain.client.render.ChainPreviewShaderMath#orderWeight}（与 preview.vert 逐值同形）。</p>
     */
    public static final double CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS = 0.55D;
    /**
     * 内部结构亮度系数：连锁预览里<b>内部格线</b>（junction 补块 / 共享顶点，即
     * {@code aAux.y == }{@value club.heiqi.qz_miner.chain.client.ChainPreviewMesh#AUX_UNDEFINED}）
     * 的顶点颜色亮度乘数。
     *
     * <p>目的（连锁预览建议 2「外轮廓强 / 内部格线弱」）：贯通管面槽位（{@code tubeEdge ∈ 0..3}）
     * 保持原亮度承担外轮廓，内部格线压暗一档，体积感来自「面亮、缝暗」而不是叠色。</p>
     *
     * <p><b>作用位置是颜色亮度</b>（{@code color = color * uInteriorDim}），<b>不乘 alpha</b>——
     * 与 {@link #CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS} 同口径：alpha 只由「距离淡出 × 逐波生长 ×
     * 包络」决定，内部压暗不得改变透明度（否则会与距离淡出抢同一个量）。{@code 1.0} = 关闭本能力
     * （逐值等于现状），默认 {@code 0.65}。</p>
     *
     * <p><b>不参与描边 pass</b>：描边壳段（{@code uOutlineWidthPx > 0}）的颜色是外轮廓专用色
     * {@code uColorChain}，压暗它等于把「外轮廓强」这条设计目标自己抹掉；门控因此额外要求
     * {@code uOutlineWidthPx <= 0}，与描边分支互斥。</p>
     *
     * <p>仅着色器后端消费：legacy 固定管线不消费 tubeEdge（其逐顶点色由 CPU 烘焙，没有该通道的
     * 语义）。消费点与 CPU 参考模型见
     * {@code chain.client.render.ChainPreviewShaderMath#interiorDim}（与 preview.vert 逐值同形）。</p>
     */
    public static final double CLIENT_PREVIEW_INTERIOR_DIM = 0.65D;
    /** 预览激活时是否取消同目标的原版方块高亮。 */
    public static final boolean CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT = false;
    /** 是否使用版本化预览输入快照（含目标去抖；本轮默认 false；接线属下一批 B1.2）。 */
    public static final boolean CLIENT_PREVIEW_VERSIONED_INPUTS = false;
    /** 是否启用统一表现投影覆盖层。 */
    public static final boolean CLIENT_PREVIEW_PRESENTATION_OVERLAY = false;
    /** 是否在 HUD 展示执行进度（已执行/匹配，B5.2 客户端世界采样；默认 off）。 */
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
    }

    /**
     * 出厂默认对象组：单组「红石矿石」，chain/area 四模式全开。
     *
     * <p>本方法是该字段的唯一真源：{@code QzMinerConfigSchema} 的 {@code defaultValue} 与配置页
     * 「恢复默认」（UILib {@code ConfigScreen.restoreDefaults()} 逐字段 {@code resetFieldToDefault}
     * ⇒ {@code DraftBuffer} ⇒ 本方法）都取这里；改默认只改本方法，不在 schema 二次抄写。</p>
     *
     * @return 恰好 1 组的不可变列表（组本身三键 id/modes/members 保序且不可变）
     */
    public static java.util.List<java.util.Map<String, Object>> objectGroups() {
        java.util.List<java.util.Map<String, Object>> groups =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        groups.add(group(
                "红石矿石",
                java.util.Arrays.asList(
                        ObjectGroupMode.CHAIN_BASE,
                        ObjectGroupMode.CHAIN_ORE,
                        ObjectGroupMode.AREA_SAME_BLOCK,
                        ObjectGroupMode.AREA_ORE),
                java.util.Arrays.asList(
                        "minecraft:redstone_ore@*",
                        "minecraft:lit_redstone_ore@*",
                        "etfuturum:deepslate_redstone_ore@*",
                        "etfuturum:deepslate_lit_redstone_ore@*")));
        return java.util.Collections.unmodifiableList(groups);
    }

    /**
     * 构造单个对象组（modes 非空，故与历史「空 modes」构造分离为显式三参 helper）。
     *
     * @param id      组标识（配置数据，不本地化）
     * @param modes   适用模式稳定标识，取自 {@link ObjectGroupMode} 常量，禁止另写字面量
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
