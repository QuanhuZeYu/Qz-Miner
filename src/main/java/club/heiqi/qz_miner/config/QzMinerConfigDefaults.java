package club.heiqi.qz_miner.config;

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
    public static final int MAX_BREAK_PER_TICK = 64;
    public static final int CABLE_REPLACE_MAX_PER_TICK = 1024;
    public static final int CHAIN_WATCHDOG_TIMEOUT_TICKS = 50;
    public static final int PARALLEL_TICK_MIN_DURATION_MS = 15;
    public static final int PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS = 640;
    public static final boolean ENABLE_UNLIMITED_ORE_FORTUNE = false;
    public static final boolean ENABLE_FORTUNE_FOR_PLACED_ORE = false;
    public static final boolean AUTO_TOOL_ENABLED = false;
    public static final String AUTO_TOOL_SEARCH_SCOPE = "inventory";
    public static final boolean AUTO_TOOL_RESTORE_ORIGINAL = true;
    public static final String AUTO_TOOL_ENCHANTMENT_POLICY = "preserve_current";
    public static final int AUTO_TOOL_MINIMUM_REMAINING_DURABILITY = 2;
    public static final int AUTO_TOOL_TARGET_STABLE_TICKS = 2;
    public static final int AUTO_TOOL_EMPTY_TARGET_GRACE_TICKS = 2;

    public static final boolean CLIENT_ENABLE_PREVIEW_RENDER = true;
    public static final int PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS = 640;
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
        target.put("general.maxBreakPerTick", Double.valueOf(MAX_BREAK_PER_TICK));
        target.put("general.cableReplaceMaxPerTick", Double.valueOf(CABLE_REPLACE_MAX_PER_TICK));
        target.put("general.chainWatchdogTimeoutTicks", Double.valueOf(CHAIN_WATCHDOG_TIMEOUT_TICKS));
        target.put("general.parallelTickMinDurationMs", Double.valueOf(PARALLEL_TICK_MIN_DURATION_MS));
        target.put("general.parallelTickServerWorkBudgetUnits",
                Double.valueOf(PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS));
        target.put("general.enableUnlimitedOreFortune", Boolean.valueOf(ENABLE_UNLIMITED_ORE_FORTUNE));
        target.put("general.enableFortuneForPlacedOre", Boolean.valueOf(ENABLE_FORTUNE_FOR_PLACED_ORE));
        target.put("general.autoToolSelection", autoToolSelection());
        target.put("client.clientEnablePreviewRender", Boolean.valueOf(CLIENT_ENABLE_PREVIEW_RENDER));
        target.put("client.parallelTickClientWorkBudgetUnits",
                Double.valueOf(PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS));
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

    /** @return UILib 普通对象替代表示：identity 固定为 default 的单元素列表。 */
    public static java.util.List<java.util.Map<String, Object>> autoToolSelection() {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<String, Object>();
        value.put("id", "default");
        value.put("enabled", Boolean.valueOf(AUTO_TOOL_ENABLED));
        value.put("searchScope", AUTO_TOOL_SEARCH_SCOPE);
        value.put("restoreOriginal", Boolean.valueOf(AUTO_TOOL_RESTORE_ORIGINAL));
        value.put("enchantmentPolicy", AUTO_TOOL_ENCHANTMENT_POLICY);
        value.put("minimumRemainingDurability", Double.valueOf(AUTO_TOOL_MINIMUM_REMAINING_DURABILITY));
        value.put("targetStableTicks", Double.valueOf(AUTO_TOOL_TARGET_STABLE_TICKS));
        value.put("emptyTargetGraceTicks", Double.valueOf(AUTO_TOOL_EMPTY_TARGET_GRACE_TICKS));
        return java.util.Collections.singletonList(java.util.Collections.unmodifiableMap(value));
    }

    /** @return 带空 modes 的三个 vanilla 默认对象组。 */
    public static java.util.List<java.util.Map<String, Object>> objectGroups() {
        java.util.List<java.util.Map<String, Object>> groups =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        groups.add(group("vanilla_logs", "minecraft:log@*", "minecraft:log2@*"));
        groups.add(group("vanilla_hay", "minecraft:hay_block@[0,4,8]"));
        groups.add(group("vanilla_redstone", "minecraft:redstone_ore@*", "minecraft:lit_redstone_ore@*"));
        return java.util.Collections.unmodifiableList(groups);
    }

    private static java.util.Map<String, Object> group(String id, String... members) {
        java.util.Map<String, Object> group = new java.util.LinkedHashMap<String, Object>();
        group.put("id", id);
        group.put("modes", java.util.Collections.<String>emptyList());
        group.put("members", java.util.Collections.unmodifiableList(java.util.Arrays.asList(members)));
        return java.util.Collections.unmodifiableMap(group);
    }
}
