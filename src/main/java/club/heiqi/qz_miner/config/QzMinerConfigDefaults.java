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
