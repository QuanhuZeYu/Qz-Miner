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
 * <p>只读、禁止 {@link Configuration#save()}。键为 schema 全路径（如 {@code general.chainRadius}）。</p>
 */
public final class LegacyCfgImporter {

    public static final String CATEGORY_GENERAL = Configuration.CATEGORY_GENERAL;
    public static final String CATEGORY_CLIENT = "client";

    private LegacyCfgImporter() {
    }

    /**
     * 瞬态读取 cfg 中已声明属性，映射为 schema path → typed 值。
     *
     * <p>不存在的属性不写入 map（由调用方用 schema 默认补齐）。读取失败返回空 map。</p>
     *
     * @param cfgFile 旧配置文件
     * @return 不可变 path→value；失败为空
     */
    public static Map<String, Object> importValues(File cfgFile) {
        if (cfgFile == null || !cfgFile.isFile() || cfgFile.length() <= 0) {
            return Collections.emptyMap();
        }

        try {
            Configuration configuration = new Configuration(cfgFile);
            // 只 load，不 save
            configuration.load();

            Map<String, Object> values = new LinkedHashMap<String, Object>();
            putString(values, configuration, CATEGORY_GENERAL, "greeting", "general.greeting");
            putInt(values, configuration, CATEGORY_GENERAL, "chainRadius", "general.chainRadius");
            putInt(values, configuration, CATEGORY_GENERAL, "chainMaxBlocks", "general.chainMaxBlocks");
            putInt(values, configuration, CATEGORY_GENERAL, "chainLoggingShellLayers", "general.chainLoggingShellLayers");
            putInt(values, configuration, CATEGORY_GENERAL, "maxBreakPerTick", "general.maxBreakPerTick");
            putInt(values, configuration, CATEGORY_GENERAL, "cableReplaceMaxPerTick", "general.cableReplaceMaxPerTick");
            putInt(values, configuration, CATEGORY_GENERAL, "chainWatchdogTimeoutTicks",
                    "general.chainWatchdogTimeoutTicks");
            putInt(values, configuration, CATEGORY_GENERAL, "parallelTickMinDurationMs",
                    "general.parallelTickMinDurationMs");
            putInt(values, configuration, CATEGORY_GENERAL, "parallelTickServerWorkBudgetUnits",
                    "general.parallelTickServerWorkBudgetUnits");
            putBoolean(values, configuration, CATEGORY_GENERAL, "enableUnlimitedOreFortune",
                    "general.enableUnlimitedOreFortune");
            putBoolean(values, configuration, CATEGORY_GENERAL, "enableFortuneForPlacedOre",
                    "general.enableFortuneForPlacedOre");

            putBoolean(values, configuration, CATEGORY_CLIENT, "clientEnablePreviewRender",
                    "client.clientEnablePreviewRender");
            putInt(values, configuration, CATEGORY_CLIENT, "parallelTickClientWorkBudgetUnits",
                    "client.parallelTickClientWorkBudgetUnits");
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewMaxRadius",
                    "client.clientPreviewMaxRadius");
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewMaxTargets",
                    "client.clientPreviewMaxTargets");
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaFadeStartRadius",
                    "client.clientPreviewAlphaFadeStartRadius");
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaFadeEndRadius",
                    "client.clientPreviewAlphaFadeEndRadius");
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaStartValue",
                    "client.clientPreviewAlphaStartValue");
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewAlphaEndValue",
                    "client.clientPreviewAlphaEndValue");

            return Collections.unmodifiableMap(values);
        } catch (RuntimeException e) {
            MyMod.LOG.warn("Legacy cfg import failed, will fall back to schema defaults: {}",
                    cfgFile.getAbsolutePath(), e);
            return Collections.emptyMap();
        }
    }

    private static void putString(Map<String, Object> values, Configuration configuration,
            String category, String name, String path) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, "");
        if (property != null) {
            values.put(path, property.getString());
        }
    }

    private static void putInt(Map<String, Object> values, Configuration configuration,
            String category, String name, String path) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, 0);
        if (property != null) {
            values.put(path, Double.valueOf(property.getInt()));
        }
    }

    private static void putBoolean(Map<String, Object> values, Configuration configuration,
            String category, String name, String path) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, false);
        if (property != null) {
            values.put(path, Boolean.valueOf(property.getBoolean()));
        }
    }

    private static void putDouble(Map<String, Object> values, Configuration configuration,
            String category, String name, String path) {
        if (!configuration.hasKey(category, name)) {
            return;
        }
        Property property = configuration.get(category, name, 0.0D);
        if (property != null) {
            values.put(path, Double.valueOf(property.getDouble()));
        }
    }
}
