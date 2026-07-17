package club.heiqi.qz_miner.config;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import club.heiqi.qz_miner.MyMod;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

/**
 * 一次性从旧 Forge {@code .cfg} 读取字段映射（唯一允许引用 {@link Configuration} 的迁移类）。
 *
 * <p>只读、禁止 {@link Configuration#save()}。Property getter 传入 {@link QzMinerConfigDefaults}
 * 真实默认（尤其 clientEnablePreviewRender=true 与 alpha 非零）。</p>
 */
public final class LegacyCfgImporter {

    public static final String CATEGORY_GENERAL = Configuration.CATEGORY_GENERAL;
    public static final String CATEGORY_CLIENT = "client";

    private LegacyCfgImporter() {
    }

    /**
     * 导入结果：区分读取失败 vs 合法文件无已知键。
     */
    public static final class ImportResult {
        public enum Status {
            /** 成功读取（values 可能为空若文件无已知键，仍 OK） */
            OK,
            /** 文件不存在或空 */
            MISSING,
            /** load/解析异常 */
            FAILED
        }

        public final Status status;
        public final Map<String, Object> values;
        public final String message;

        ImportResult(Status status, Map<String, Object> values, String message) {
            this.status = status;
            this.values = values == null
                    ? Collections.<String, Object>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
            this.message = message;
        }

        public static ImportResult ok(Map<String, Object> values) {
            return new ImportResult(Status.OK, values, null);
        }

        public static ImportResult missing(String message) {
            return new ImportResult(Status.MISSING, Collections.<String, Object>emptyMap(), message);
        }

        public static ImportResult failed(String message) {
            return new ImportResult(Status.FAILED, Collections.<String, Object>emptyMap(), message);
        }
    }

    /**
     * 瞬态读取 cfg 中已声明属性。
     *
     * @param cfgFile 旧配置文件
     * @return ImportResult（不把 FAILED 与「无键」混为 empty map）
     */
    public static ImportResult importValues(File cfgFile) {
        if (cfgFile == null || !cfgFile.isFile() || cfgFile.length() <= 0) {
            return ImportResult.missing("cfg missing or empty");
        }

        try {
            Configuration configuration = new Configuration(cfgFile);
            configuration.load();

            Map<String, Object> values = new LinkedHashMap<String, Object>();
            putString(values, configuration, CATEGORY_GENERAL, "greeting", "general.greeting",
                    QzMinerConfigDefaults.GREETING);
            putInt(values, configuration, CATEGORY_GENERAL, "chainRadius", "general.chainRadius",
                    QzMinerConfigDefaults.CHAIN_RADIUS);
            putInt(values, configuration, CATEGORY_GENERAL, "chainMaxBlocks", "general.chainMaxBlocks",
                    QzMinerConfigDefaults.CHAIN_MAX_BLOCKS);
            putInt(values, configuration, CATEGORY_GENERAL, "chainLoggingShellLayers",
                    "general.chainLoggingShellLayers", QzMinerConfigDefaults.CHAIN_LOGGING_SHELL_LAYERS);
            putInt(values, configuration, CATEGORY_GENERAL, "maxBreakPerTick", "general.maxBreakPerTick",
                    QzMinerConfigDefaults.MAX_BREAK_PER_TICK);
            putInt(values, configuration, CATEGORY_GENERAL, "cableReplaceMaxPerTick",
                    "general.cableReplaceMaxPerTick", QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK);
            putInt(values, configuration, CATEGORY_GENERAL, "chainWatchdogTimeoutTicks",
                    "general.chainWatchdogTimeoutTicks", QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS);
            putInt(values, configuration, CATEGORY_GENERAL, "parallelTickMinDurationMs",
                    "general.parallelTickMinDurationMs", QzMinerConfigDefaults.PARALLEL_TICK_MIN_DURATION_MS);
            putInt(values, configuration, CATEGORY_GENERAL, "parallelTickServerWorkBudgetUnits",
                    "general.parallelTickServerWorkBudgetUnits",
                    QzMinerConfigDefaults.PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS);
            putBoolean(values, configuration, CATEGORY_GENERAL, "enableUnlimitedOreFortune",
                    "general.enableUnlimitedOreFortune", QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE);
            putBoolean(values, configuration, CATEGORY_GENERAL, "enableFortuneForPlacedOre",
                    "general.enableFortuneForPlacedOre", QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE);

            putBoolean(values, configuration, CATEGORY_CLIENT, "clientEnablePreviewRender",
                    "client.clientEnablePreviewRender", QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER);
            putInt(values, configuration, CATEGORY_CLIENT, "parallelTickClientWorkBudgetUnits",
                    "client.parallelTickClientWorkBudgetUnits",
                    QzMinerConfigDefaults.PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewMaxRadius",
                    "client.clientPreviewMaxRadius", QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewMaxTargets",
                    "client.clientPreviewMaxTargets", QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaFadeStartRadius",
                    "client.clientPreviewAlphaFadeStartRadius",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaFadeEndRadius",
                    "client.clientPreviewAlphaFadeEndRadius",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaStartValue",
                    "client.clientPreviewAlphaStartValue",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaEndValue",
                    "client.clientPreviewAlphaEndValue",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE);

            return ImportResult.ok(values);
        } catch (RuntimeException e) {
            MyMod.LOG.warn("Legacy cfg import failed: {}", cfgFile.getAbsolutePath(), e);
            return ImportResult.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static void putString(Map<String, Object> values, Configuration configuration,
            String category, String name, String path, String defaultValue) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, defaultValue);
        if (property != null) {
            values.put(path, property.getString());
        }
    }

    private static void putInt(Map<String, Object> values, Configuration configuration,
            String category, String name, String path, int defaultValue) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, defaultValue);
        if (property != null) {
            values.put(path, Double.valueOf(property.getInt()));
        }
    }

    private static void putBoolean(Map<String, Object> values, Configuration configuration,
            String category, String name, String path, boolean defaultValue) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, defaultValue);
        if (property != null) {
            values.put(path, Boolean.valueOf(property.getBoolean()));
        }
    }

    private static void putDouble(Map<String, Object> values, Configuration configuration,
            String category, String name, String path, double defaultValue) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, defaultValue);
        if (property != null) {
            values.put(path, Double.valueOf(property.getDouble()));
        }
    }
}
