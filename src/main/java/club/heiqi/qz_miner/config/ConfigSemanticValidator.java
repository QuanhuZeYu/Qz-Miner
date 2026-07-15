package club.heiqi.qz_miner.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.DraftValidator;
import club.heiqi.config.runtime.DraftView;
import club.heiqi.config.runtime.ValidationResult;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.ToolSelectorParser;

/**
 * Qz-Miner 配置的共用语义读取器与 UILib 提交前校验器。
 *
 * <p>启动校验通过 {@link ConfigManager#openDraft()} 捕获 Authority 全表后进入同一读取器；
 * ConfigUI 保存则由 UILib 直接传入 {@link DraftView}。非法值不夹取、不 round。</p>
 */
public final class ConfigSemanticValidator {

    private static final DraftValidator DRAFT_VALIDATOR = new DraftValidator() {
        @Override
        public ValidationResult validate(DraftView draft) {
            return readAndValidate(draft).result.asValidationResult();
        }
    };

    private ConfigSemanticValidator() {
    }

    /** @return 无状态 DraftValidator 单例 */
    public static DraftValidator draftValidator() {
        return DRAFT_VALIDATOR;
    }

    /**
     * 原子捕获 manager 当前 Authority 全表并执行共用语义读取。
     *
     * @param manager 配置 manager
     * @return 校验与快照结果
     */
    public static ParseOutcome captureAndValidate(ConfigManager manager) {
        if (manager == null) {
            return ParseOutcome.invalid(singleError(DraftValidator.GLOBAL_ERROR_PATH, "manager is null"));
        }
        DraftBuffer draft = manager.openDraft();
        Map<String, Object> values = draft.draftSnapshot();
        Collection<String> paths = draft.fieldPaths();
        return readAndValidate(new FrozenDraftView(values, paths));
    }

    /**
     * 从 DraftView 读取全部字段并校验。
     *
     * @param draft 只读草稿视图
     * @return 校验与快照结果
     */
    static ParseOutcome readAndValidate(DraftView draft) {
        if (draft == null) {
            return ParseOutcome.invalid(singleError(DraftValidator.GLOBAL_ERROR_PATH, "draft is null"));
        }
        Map<String, Object> typed = new LinkedHashMap<String, Object>();
        Map<String, String> errors = new LinkedHashMap<String, String>();

        putString(typed, errors, draft, "general.greeting");
        putIntNumber(typed, errors, draft, "general.chainRadius", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "general.chainMaxBlocks", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "general.chainLoggingShellLayers", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "general.maxBreakPerTick", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "general.cableReplaceMaxPerTick", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "general.chainWatchdogTimeoutTicks", 20, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "general.parallelTickMinDurationMs", 10, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "general.parallelTickServerWorkBudgetUnits", 1, Integer.MAX_VALUE);
        putBoolean(typed, errors, draft, "general.enableUnlimitedOreFortune");
        putBoolean(typed, errors, draft, "general.enableFortuneForPlacedOre");
        putBoolean(typed, errors, draft, "client.clientEnablePreviewRender");
        putBoolean(typed, errors, draft, "client.autoToolSwapEnabled");
        putToolSelectors(typed, errors, draft);
        putIntNumber(typed, errors, draft, "client.parallelTickClientWorkBudgetUnits", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "client.clientPreviewMaxRadius", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, draft, "client.clientPreviewMaxTargets", 1, Integer.MAX_VALUE);
        putDoubleNumber(typed, errors, draft, "client.clientPreviewAlphaFadeStartRadius", 0.0D, Double.MAX_VALUE);
        putDoubleNumber(typed, errors, draft, "client.clientPreviewAlphaFadeEndRadius", 0.0D, Double.MAX_VALUE);
        putDoubleNumber(typed, errors, draft, "client.clientPreviewAlphaStartValue", 0.0D, 1.0D);
        putDoubleNumber(typed, errors, draft, "client.clientPreviewAlphaEndValue", 0.0D, 1.0D);
        putObjectGroups(typed, errors, draft);

        validateCrossFields(typed, errors);
        if (!errors.isEmpty()) {
            return ParseOutcome.invalid(errors);
        }
        return ParseOutcome.valid(new ValidatedSnapshot(typed));
    }

    private static void validateCrossFields(Map<String, Object> typed, Map<String, String> errors) {
        String fadeStartPath = "client.clientPreviewAlphaFadeStartRadius";
        String fadeEndPath = "client.clientPreviewAlphaFadeEndRadius";
        if (typed.containsKey(fadeStartPath) && typed.containsKey(fadeEndPath)) {
            double fadeStart = ((Double) typed.get(fadeStartPath)).doubleValue();
            double fadeEnd = ((Double) typed.get(fadeEndPath)).doubleValue();
            if (fadeEnd < fadeStart + QzMinerConfigDefaults.ALPHA_FADE_MIN_SPAN) {
                String message = "fadeEnd must be >= fadeStart + " + QzMinerConfigDefaults.ALPHA_FADE_MIN_SPAN;
                errors.put(fadeStartPath, message);
                errors.put(fadeEndPath, message);
            }
        }

        String alphaStartPath = "client.clientPreviewAlphaStartValue";
        String alphaEndPath = "client.clientPreviewAlphaEndValue";
        if (typed.containsKey(alphaStartPath) && typed.containsKey(alphaEndPath)) {
            double alphaStart = ((Double) typed.get(alphaStartPath)).doubleValue();
            double alphaEnd = ((Double) typed.get(alphaEndPath)).doubleValue();
            if (alphaEnd > alphaStart) {
                String message = "alphaEnd must be <= alphaStart";
                errors.put(alphaStartPath, message);
                errors.put(alphaEndPath, message);
            }
        }
    }

    private static void putObjectGroups(Map<String, Object> typed, Map<String, String> errors,
            DraftView draft) {
        String path = "client.objectGroups";
        ObjectGroupParser.ParseResult parsed = ObjectGroupParser.parse(draft.getDraft(path));
        if (!parsed.isValid()) {
            errors.putAll(parsed.errors());
            return;
        }
        typed.put(path, parsed.rules());
    }

    private static void putToolSelectors(Map<String, Object> typed, Map<String, String> errors,
            DraftView draft) {
        String path = "client.autoToolPrioritySelectors";
        ToolSelectorParser.ParseResult parsed = ToolSelectorParser.parseList(draft.getDraft(path), path);
        if (!parsed.isValid()) {
            errors.putAll(parsed.errors());
            return;
        }
        typed.put(path, parsed.selectors());
    }

    private static void putString(Map<String, Object> typed, Map<String, String> errors,
            DraftView draft, String path) {
        Object raw = draft.getDraft(path);
        if (!(raw instanceof String)) {
            errors.put(path, path + " must be STRING, got " + typeName(raw));
            return;
        }
        typed.put(path, raw);
    }

    private static void putBoolean(Map<String, Object> typed, Map<String, String> errors,
            DraftView draft, String path) {
        Object raw = draft.getDraft(path);
        if (!(raw instanceof Boolean)) {
            errors.put(path, path + " must be BOOLEAN, got " + typeName(raw));
            return;
        }
        typed.put(path, raw);
    }

    private static void putIntNumber(Map<String, Object> typed, Map<String, String> errors,
            DraftView draft, String path, int min, int max) {
        Object raw = draft.getDraft(path);
        if (!(raw instanceof Number)) {
            errors.put(path, path + " must be NUMBER, got " + typeName(raw));
            return;
        }
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value)) {
            errors.put(path, path + " must be finite, got " + value);
        } else if (value != Math.rint(value)) {
            errors.put(path, path + " must be integer-valued, got " + value);
        } else if (value < min || value > max) {
            errors.put(path, path + " out of range [" + min + "," + max + "], got " + value);
        } else {
            typed.put(path, Double.valueOf(value));
        }
    }

    private static void putDoubleNumber(Map<String, Object> typed, Map<String, String> errors,
            DraftView draft, String path, double min, double max) {
        Object raw = draft.getDraft(path);
        if (!(raw instanceof Number)) {
            errors.put(path, path + " must be NUMBER, got " + typeName(raw));
            return;
        }
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value)) {
            errors.put(path, path + " must be finite, got " + value);
        } else if (value < min || value > max) {
            errors.put(path, path + " out of range [" + min + "," + max + "], got " + value);
        } else {
            typed.put(path, Double.valueOf(value));
        }
    }

    private static Map<String, String> singleError(String path, String message) {
        Map<String, String> errors = new LinkedHashMap<String, String>();
        errors.put(path, message);
        return errors;
    }

    private static String typeName(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }

    /** 语义校验结果。 */
    public static final class Result {
        private final Map<String, String> errors;

        Result(Map<String, String> errors) {
            this.errors = Collections.unmodifiableMap(new LinkedHashMap<String, String>(errors));
        }

        /** @return 是否通过 */
        public boolean isValid() {
            return errors.isEmpty();
        }

        /** @return path 到错误消息 */
        public Map<String, String> errors() {
            return errors;
        }

        /** @return 确定性摘要 */
        public String summary() {
            if (errors.isEmpty()) {
                return "ok";
            }
            StringBuilder out = new StringBuilder();
            for (String message : errors.values()) {
                if (out.length() > 0) {
                    out.append("; ");
                }
                out.append(message);
            }
            return out.toString();
        }

        ValidationResult asValidationResult() {
            return ValidationResult.of(errors);
        }
    }

    /** 读取与快照生成结果。 */
    public static final class ParseOutcome {
        public final Result result;
        public final ValidatedSnapshot snapshot;

        private ParseOutcome(Result result, ValidatedSnapshot snapshot) {
            this.result = result;
            this.snapshot = snapshot;
        }

        /** @return 是否通过且已生成快照 */
        public boolean isValid() {
            return result.isValid() && snapshot != null;
        }

        static ParseOutcome valid(ValidatedSnapshot snapshot) {
            return new ParseOutcome(new Result(Collections.<String, String>emptyMap()), snapshot);
        }

        static ParseOutcome invalid(Map<String, String> errors) {
            return new ParseOutcome(new Result(errors), null);
        }
    }

    /** 已严格校验的只读派生发布载荷。 */
    public static final class ValidatedSnapshot {
        public final String greeting;
        public final int chainRadius;
        public final int chainMaxBlocks;
        public final int chainLoggingShellLayers;
        public final int maxBreakPerTick;
        public final int cableReplaceMaxPerTick;
        public final int chainWatchdogTimeoutTicks;
        public final int parallelTickMinDurationMs;
        public final int parallelTickServerWorkBudgetUnits;
        public final boolean enableUnlimitedOreFortune;
        public final boolean enableFortuneForPlacedOre;
        public final boolean clientEnablePreviewRender;
        public final boolean autoToolSwapEnabled;
        public final java.util.List<ToolSelector> autoToolPrioritySelectors;
        public final int parallelTickClientWorkBudgetUnits;
        public final int clientPreviewMaxRadius;
        public final int clientPreviewMaxTargets;
        public final double clientPreviewAlphaFadeStartRadius;
        public final double clientPreviewAlphaFadeEndRadius;
        public final double clientPreviewAlphaStartValue;
        public final double clientPreviewAlphaEndValue;
        public final ObjectGroupRuleSet objectGroups;

        ValidatedSnapshot(Map<String, Object> typed) {
            greeting = (String) typed.get("general.greeting");
            chainRadius = exactInt(typed, "general.chainRadius");
            chainMaxBlocks = exactInt(typed, "general.chainMaxBlocks");
            chainLoggingShellLayers = exactInt(typed, "general.chainLoggingShellLayers");
            maxBreakPerTick = exactInt(typed, "general.maxBreakPerTick");
            cableReplaceMaxPerTick = exactInt(typed, "general.cableReplaceMaxPerTick");
            chainWatchdogTimeoutTicks = exactInt(typed, "general.chainWatchdogTimeoutTicks");
            parallelTickMinDurationMs = exactInt(typed, "general.parallelTickMinDurationMs");
            parallelTickServerWorkBudgetUnits = exactInt(typed, "general.parallelTickServerWorkBudgetUnits");
            enableUnlimitedOreFortune = ((Boolean) typed.get("general.enableUnlimitedOreFortune")).booleanValue();
            enableFortuneForPlacedOre = ((Boolean) typed.get("general.enableFortuneForPlacedOre")).booleanValue();
            clientEnablePreviewRender = ((Boolean) typed.get("client.clientEnablePreviewRender")).booleanValue();
            autoToolSwapEnabled = ((Boolean) typed.get("client.autoToolSwapEnabled")).booleanValue();
            autoToolPrioritySelectors = immutableSelectors(typed.get("client.autoToolPrioritySelectors"));
            parallelTickClientWorkBudgetUnits = exactInt(typed, "client.parallelTickClientWorkBudgetUnits");
            clientPreviewMaxRadius = exactInt(typed, "client.clientPreviewMaxRadius");
            clientPreviewMaxTargets = exactInt(typed, "client.clientPreviewMaxTargets");
            clientPreviewAlphaFadeStartRadius = number(typed, "client.clientPreviewAlphaFadeStartRadius");
            clientPreviewAlphaFadeEndRadius = number(typed, "client.clientPreviewAlphaFadeEndRadius");
            clientPreviewAlphaStartValue = number(typed, "client.clientPreviewAlphaStartValue");
            clientPreviewAlphaEndValue = number(typed, "client.clientPreviewAlphaEndValue");
            objectGroups = (ObjectGroupRuleSet) typed.get("client.objectGroups");
        }

        @SuppressWarnings("unchecked")
        private static java.util.List<ToolSelector> immutableSelectors(Object value) {
            return java.util.Collections.unmodifiableList(
                    new java.util.ArrayList<ToolSelector>((java.util.List<ToolSelector>) value));
        }

        private static int exactInt(Map<String, Object> typed, String path) {
            return (int) number(typed, path);
        }

        private static double number(Map<String, Object> typed, String path) {
            return ((Number) typed.get(path)).doubleValue();
        }
    }

    /** DraftBuffer 捕获结果的只读 DraftView 适配。 */
    private static final class FrozenDraftView implements DraftView {
        private final Map<String, Object> values;
        private final Collection<String> paths;

        FrozenDraftView(Map<String, Object> values, Collection<String> paths) {
            this.values = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(values));
            this.paths = Collections.unmodifiableList(new ArrayList<String>(paths));
        }

        @Override
        public Object getDraft(String path) {
            return values.get(path);
        }

        @Override
        public Map<String, Object> draftSnapshot() {
            return values;
        }

        @Override
        public Collection<String> fieldPaths() {
            return paths;
        }
    }
}
