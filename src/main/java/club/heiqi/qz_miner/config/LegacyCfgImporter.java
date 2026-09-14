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
            putInt(values, configuration, CATEGORY_GENERAL, "cableReplaceMaxPerTick",
                    "general.cableReplaceMaxPerTick", QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK);
            putInt(values, configuration, CATEGORY_GENERAL, "chainWatchdogTimeoutTicks",
                    "general.chainWatchdogTimeoutTicks", QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS);
            putLegacyTickDuration(values, configuration);
            putBoolean(values, configuration, CATEGORY_GENERAL, "enableUnlimitedOreFortune",
                    "general.enableUnlimitedOreFortune", QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE);
            putBoolean(values, configuration, CATEGORY_GENERAL, "enableFortuneForPlacedOre",
                    "general.enableFortuneForPlacedOre", QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE);

            putBoolean(values, configuration, CATEGORY_CLIENT, "clientEnablePreviewRender",
                    "client.clientEnablePreviewRender", QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER);
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
            putString(values, configuration, CATEGORY_CLIENT, "clientPreviewRenderBackend",
                    "client.clientPreviewRenderBackend",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_RENDER_BACKEND);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewBarThickness",
                    "client.clientPreviewBarThickness",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_BAR_THICKNESS);
            putString(values, configuration, CATEGORY_CLIENT, "clientPreviewColorSource",
                    "client.clientPreviewColorSource",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SOURCE);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewColorPrimary",
                    "client.clientPreviewColorPrimary",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_PRIMARY);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewColorSecondary",
                    "client.clientPreviewColorSecondary",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SECONDARY);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewColorRemote",
                    "client.clientPreviewColorRemote",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_REMOTE);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewColorTruncated",
                    "client.clientPreviewColorTruncated",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_TRUNCATED);
            putString(values, configuration, CATEGORY_CLIENT, "clientPreviewDepthMode",
                    "client.clientPreviewDepthMode",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_DEPTH_MODE);
            putString(values, configuration, CATEGORY_CLIENT, "clientPreviewAnimation",
                    "client.clientPreviewAnimation",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewAnimationDurationMs",
                    "client.clientPreviewAnimationDurationMs",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_DURATION_MS);
            putString(values, configuration, CATEGORY_CLIENT, "clientPreviewAnimationPhase",
                    "client.clientPreviewAnimationPhase",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_PHASE);
            putString(values, configuration, CATEGORY_CLIENT, "clientPreviewFadeMode",
                    "client.clientPreviewFadeMode",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_MODE);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewFadeRefreshDistance",
                    "client.clientPreviewFadeRefreshDistance",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_REFRESH_DISTANCE);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewFadeFallbackMs",
                    "client.clientPreviewFadeFallbackMs",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_FALLBACK_MS);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewMinScreenWidthPx",
                    "client.clientPreviewMinScreenWidthPx",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewOutlineWidthPx",
                    "client.clientPreviewOutlineWidthPx",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_OUTLINE_WIDTH_PX);
            putBoolean(values, configuration, CATEGORY_CLIENT, "clientPreviewFaceShading",
                    "client.clientPreviewFaceShading",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_FACE_SHADING);
            putBoolean(values, configuration, CATEGORY_CLIENT, "clientPreviewTruncationSignal",
                    "client.clientPreviewTruncationSignal",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_TRUNCATION_SIGNAL);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewMaxTargetsHardCap",
                    "client.clientPreviewMaxTargetsHardCap",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP);
            putString(values, configuration, CATEGORY_CLIENT, "clientPreviewLod",
                    "client.clientPreviewLod",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_LOD);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewLodMinAlpha",
                    "client.clientPreviewLodMinAlpha",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_LOD_MIN_ALPHA);
            putDouble(values, configuration, CATEGORY_CLIENT, "clientPreviewOrderMinBrightness",
                    "client.clientPreviewOrderMinBrightness",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_ORDER_MIN_BRIGHTNESS);
            putBoolean(values, configuration, CATEGORY_CLIENT, "clientPreviewSuppressVanillaHighlight",
                    "client.clientPreviewSuppressVanillaHighlight",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT);
            putBoolean(values, configuration, CATEGORY_CLIENT, "clientPreviewVersionedInputs",
                    "client.clientPreviewVersionedInputs",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_VERSIONED_INPUTS);
            putBoolean(values, configuration, CATEGORY_CLIENT, "clientPreviewPresentationOverlay",
                    "client.clientPreviewPresentationOverlay",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_PRESENTATION_OVERLAY);
            putBoolean(values, configuration, CATEGORY_CLIENT, "clientPreviewExecutionProgress",
                    "client.clientPreviewExecutionProgress",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_EXECUTION_PROGRESS);
            putBoolean(values, configuration, CATEGORY_CLIENT, "clientPreviewBackendDiagnostics",
                    "client.clientPreviewBackendDiagnostics",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_BACKEND_DIAGNOSTICS);
            putInt(values, configuration, CATEGORY_CLIENT, "clientPreviewRemoteTimeoutMs",
                    "client.clientPreviewRemoteTimeoutMs",
                    QzMinerConfigDefaults.CLIENT_PREVIEW_REMOTE_TIMEOUT_MS);

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

    private static void putLegacyTickDuration(
            Map<String, Object> values, Configuration configuration) {
        if (!configuration.hasKey(CATEGORY_GENERAL, "parallelTickMinDurationMs")) {
            return;
        }
        Property property = configuration.get(CATEGORY_GENERAL, "parallelTickMinDurationMs",
                QzMinerConfigDefaults.TICK_BUDGET_MS);
        if (property != null) {
            int legacyValue = property.getInt(QzMinerConfigDefaults.TICK_BUDGET_MS);
            values.put("general.tickBudgetMs", Double.valueOf(
                    ConfigBootstrap.normalizeLegacyCfgTickDuration(legacyValue)));
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
