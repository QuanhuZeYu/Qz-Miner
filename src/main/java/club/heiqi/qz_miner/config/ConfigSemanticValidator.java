package club.heiqi.qz_miner.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import club.heiqi.config.runtime.Authority;

/**
 * 配置语义严格校验（回灌前 / BATCH_SAVE 补偿用）。
 *
 * <p>UILib 4.5.1 {@code DraftBuffer.validateAll} 仅覆盖 required / NUMBER min-max / STRING 长度 /
 * CHOICE 选项，<b>不</b>校验 finite、整数字段 {@code value == Math.rint(value)}、以及 alpha 交叉约束。
 * 本类在 schema 范围校验之上补严格语义；非法值不夹取、不 round，直接拒绝。</p>
 */
public final class ConfigSemanticValidator {

    private ConfigSemanticValidator() {
    }

    /**
     * 校验结果。
     */
    public static final class Result {
        private final List<String> errors;

        Result(List<String> errors) {
            this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<String> errors() {
            return errors;
        }

        public String summary() {
            if (errors.isEmpty()) {
                return "ok";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(errors.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * 已通过严格校验的不可变快照（运行字段发布用）。
     */
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
        public final int parallelTickClientWorkBudgetUnits;
        public final int clientPreviewMaxRadius;
        public final int clientPreviewMaxTargets;
        public final double clientPreviewAlphaFadeStartRadius;
        public final double clientPreviewAlphaFadeEndRadius;
        public final double clientPreviewAlphaStartValue;
        public final double clientPreviewAlphaEndValue;
        /** path → typed 值（String/Double/Boolean），供恢复 Authority 草稿。 */
        public final Map<String, Object> typedByPath;

        ValidatedSnapshot(Map<String, Object> typed) {
            this.typedByPath = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(typed));
            this.greeting = (String) typed.get("general.greeting");
            this.chainRadius = exactInt((Double) typed.get("general.chainRadius"));
            this.chainMaxBlocks = exactInt((Double) typed.get("general.chainMaxBlocks"));
            this.chainLoggingShellLayers = exactInt((Double) typed.get("general.chainLoggingShellLayers"));
            this.maxBreakPerTick = exactInt((Double) typed.get("general.maxBreakPerTick"));
            this.cableReplaceMaxPerTick = exactInt((Double) typed.get("general.cableReplaceMaxPerTick"));
            this.chainWatchdogTimeoutTicks = exactInt((Double) typed.get("general.chainWatchdogTimeoutTicks"));
            this.parallelTickMinDurationMs = exactInt((Double) typed.get("general.parallelTickMinDurationMs"));
            this.parallelTickServerWorkBudgetUnits =
                    exactInt((Double) typed.get("general.parallelTickServerWorkBudgetUnits"));
            this.enableUnlimitedOreFortune = (Boolean) typed.get("general.enableUnlimitedOreFortune");
            this.enableFortuneForPlacedOre = (Boolean) typed.get("general.enableFortuneForPlacedOre");
            this.clientEnablePreviewRender = (Boolean) typed.get("client.clientEnablePreviewRender");
            this.parallelTickClientWorkBudgetUnits =
                    exactInt((Double) typed.get("client.parallelTickClientWorkBudgetUnits"));
            this.clientPreviewMaxRadius = exactInt((Double) typed.get("client.clientPreviewMaxRadius"));
            this.clientPreviewMaxTargets = exactInt((Double) typed.get("client.clientPreviewMaxTargets"));
            this.clientPreviewAlphaFadeStartRadius =
                    ((Double) typed.get("client.clientPreviewAlphaFadeStartRadius")).doubleValue();
            this.clientPreviewAlphaFadeEndRadius =
                    ((Double) typed.get("client.clientPreviewAlphaFadeEndRadius")).doubleValue();
            this.clientPreviewAlphaStartValue =
                    ((Double) typed.get("client.clientPreviewAlphaStartValue")).doubleValue();
            this.clientPreviewAlphaEndValue =
                    ((Double) typed.get("client.clientPreviewAlphaEndValue")).doubleValue();
        }

        private static int exactInt(Double d) {
            return (int) d.doubleValue();
        }
    }

    /**
     * 从 Authority 读取已知字段，严格校验后返回快照。
     *
     * @param authority 权威源
     * @return 校验结果；成功时 snapshot 非 null
     */
    public static ParseOutcome parseAndValidate(Authority authority) {
        if (authority == null) {
            return ParseOutcome.invalid(Collections.singletonList("authority is null"));
        }
        Map<String, Object> typed = new LinkedHashMap<String, Object>();
        List<String> errors = new ArrayList<String>();

        putString(typed, errors, authority, "general.greeting");
        putIntNumber(typed, errors, authority, "general.chainRadius", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "general.chainMaxBlocks", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "general.chainLoggingShellLayers", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "general.maxBreakPerTick", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "general.cableReplaceMaxPerTick", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "general.chainWatchdogTimeoutTicks", 20, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "general.parallelTickMinDurationMs", 10, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "general.parallelTickServerWorkBudgetUnits", 1, Integer.MAX_VALUE);
        putBool(typed, errors, authority, "general.enableUnlimitedOreFortune");
        putBool(typed, errors, authority, "general.enableFortuneForPlacedOre");

        putBool(typed, errors, authority, "client.clientEnablePreviewRender");
        putIntNumber(typed, errors, authority, "client.parallelTickClientWorkBudgetUnits", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "client.clientPreviewMaxRadius", 1, Integer.MAX_VALUE);
        putIntNumber(typed, errors, authority, "client.clientPreviewMaxTargets", 1, Integer.MAX_VALUE);
        putDoubleNumber(typed, errors, authority, "client.clientPreviewAlphaFadeStartRadius", 0.0D, Double.MAX_VALUE);
        putDoubleNumber(typed, errors, authority, "client.clientPreviewAlphaFadeEndRadius", 0.0D, Double.MAX_VALUE);
        putDoubleNumber(typed, errors, authority, "client.clientPreviewAlphaStartValue", 0.0D, 1.0D);
        putDoubleNumber(typed, errors, authority, "client.clientPreviewAlphaEndValue", 0.0D, 1.0D);

        if (errors.isEmpty()) {
            double fadeStart = ((Double) typed.get("client.clientPreviewAlphaFadeStartRadius")).doubleValue();
            double fadeEnd = ((Double) typed.get("client.clientPreviewAlphaFadeEndRadius")).doubleValue();
            double alphaStart = ((Double) typed.get("client.clientPreviewAlphaStartValue")).doubleValue();
            double alphaEnd = ((Double) typed.get("client.clientPreviewAlphaEndValue")).doubleValue();
            if (fadeEnd < fadeStart + QzMinerConfigDefaults.ALPHA_FADE_MIN_SPAN) {
                errors.add("client.clientPreviewAlphaFadeEndRadius must be >= fadeStart + "
                        + QzMinerConfigDefaults.ALPHA_FADE_MIN_SPAN + " (got " + fadeEnd + " vs " + fadeStart + ")");
            }
            if (alphaEnd > alphaStart) {
                errors.add("client.clientPreviewAlphaEndValue must be <= alphaStart (got "
                        + alphaEnd + " > " + alphaStart + ")");
            }
        }

        if (!errors.isEmpty()) {
            return ParseOutcome.invalid(errors);
        }
        return ParseOutcome.valid(new ValidatedSnapshot(typed));
    }

    /**
     * parse 结果容器。
     */
    public static final class ParseOutcome {
        public final Result result;
        public final ValidatedSnapshot snapshot;

        private ParseOutcome(Result result, ValidatedSnapshot snapshot) {
            this.result = result;
            this.snapshot = snapshot;
        }

        public boolean isValid() {
            return result.isValid() && snapshot != null;
        }

        static ParseOutcome valid(ValidatedSnapshot snapshot) {
            return new ParseOutcome(new Result(Collections.<String>emptyList()), snapshot);
        }

        static ParseOutcome invalid(List<String> errors) {
            return new ParseOutcome(new Result(errors), null);
        }
    }

    private static void putString(Map<String, Object> typed, List<String> errors,
            Authority authority, String path) {
        Object raw = authority.get(path);
        if (raw == null) {
            // schema 缺字段应由 Authority 补默认；仍 null 则用 Defaults
            typed.put(path, QzMinerConfigDefaults.GREETING);
            return;
        }
        if (!(raw instanceof String)) {
            errors.add(path + " must be STRING, got " + typeName(raw));
            return;
        }
        typed.put(path, raw);
    }

    private static void putBool(Map<String, Object> typed, List<String> errors,
            Authority authority, String path) {
        Object raw = authority.get(path);
        if (raw == null) {
            errors.add(path + " missing boolean");
            return;
        }
        if (!(raw instanceof Boolean)) {
            errors.add(path + " must be BOOLEAN, got " + typeName(raw));
            return;
        }
        typed.put(path, raw);
    }

    private static void putIntNumber(Map<String, Object> typed, List<String> errors,
            Authority authority, String path, int min, int max) {
        Object raw = authority.get(path);
        if (raw == null) {
            errors.add(path + " missing number");
            return;
        }
        if (!(raw instanceof Number)) {
            errors.add(path + " must be NUMBER, got " + typeName(raw));
            return;
        }
        double v = ((Number) raw).doubleValue();
        if (!Double.isFinite(v)) {
            errors.add(path + " must be finite, got " + v);
            return;
        }
        // 整数语义：禁止 12.6 / 2.999 等非整数；不 Math.round 掩盖
        if (v != Math.rint(v)) {
            errors.add(path + " must be integer-valued, got " + v);
            return;
        }
        if (v < min || v > max) {
            errors.add(path + " out of range [" + min + "," + max + "], got " + v);
            return;
        }
        typed.put(path, Double.valueOf(v));
    }

    private static void putDoubleNumber(Map<String, Object> typed, List<String> errors,
            Authority authority, String path, double min, double max) {
        Object raw = authority.get(path);
        if (raw == null) {
            errors.add(path + " missing number");
            return;
        }
        if (!(raw instanceof Number)) {
            errors.add(path + " must be NUMBER, got " + typeName(raw));
            return;
        }
        double v = ((Number) raw).doubleValue();
        if (!Double.isFinite(v)) {
            errors.add(path + " must be finite, got " + v);
            return;
        }
        if (v < min || v > max) {
            errors.add(path + " out of range [" + min + "," + max + "], got " + v);
            return;
        }
        typed.put(path, Double.valueOf(v));
    }

    private static String typeName(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName();
    }
}
