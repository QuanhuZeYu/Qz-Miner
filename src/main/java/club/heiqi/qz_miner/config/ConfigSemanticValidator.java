package club.heiqi.qz_miner.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigException;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.qz_miner.network.ObjectGroupWireConfig;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.ToolSelectorParser;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/** 文档自身校验类型与范围；此处只解析业务规则并检查跨键约束，不修改文档。 */
public final class ConfigSemanticValidator {
    private ConfigSemanticValidator() { }

    public static ParseOutcome validate(ConfigDocument document) {
        if (document == null || document.schema() != QzMinerConfigSchema.SCHEMA) {
            throw new IllegalArgumentException("需要 Miner 唯一 schema 的配置文档");
        }
        Map<String, Object> typed = new LinkedHashMap<String, Object>();
        for (ConfigKey<?> key : document.schema().keys()) typed.put(key.name(), document.get(key));
        Map<String, String> errors = new LinkedHashMap<String, String>();
        typed.put("client.tunnelDirectionSource", TunnelDirectionSource.fromId(
                document.get(QzMinerConfigSchema.TUNNEL_DIRECTION_SOURCE)));
        ToolSelectorParser.ParseResult selectors = ToolSelectorParser.parseList(
                document.get(QzMinerConfigSchema.AUTO_TOOL_PRIORITY_SELECTORS), "client.autoToolPrioritySelectors");
        if (selectors.isValid()) typed.put("client.autoToolPrioritySelectors", selectors.selectors());
        else errors.putAll(selectors.errors());
        ObjectGroupParser.ParseResult groups = ObjectGroupParser.parse(document.get(QzMinerConfigSchema.OBJECT_GROUPS));
        if (groups.isValid()) {
            try {
                // 提交前复用 wire 编码边界校验总容量，使已落盘候选可完整同步。
                ObjectGroupWireConfig.fromRuleSet(0L, groups.rules());
                typed.put("client.objectGroups", groups.rules());
            } catch (IllegalArgumentException failure) {
                errors.put("client.objectGroups", failure.getMessage());
            }
        } else errors.putAll(groups.errors());
        validateCrossFields(typed, errors);
        return errors.isEmpty() ? ParseOutcome.valid(new ValidatedSnapshot(typed)) : ParseOutcome.invalid(errors);
    }

    static ValidatedSnapshot requireValid(ConfigDocument document) {
        ParseOutcome outcome = validate(document);
        if (!outcome.isValid()) {
            String path = outcome.result.errors().keySet().iterator().next();
            throw new ConfigException(path, outcome.result.summary());
        }
        return outcome.snapshot;
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
        public final int cableReplaceMaxPerTick;
        public final int chainWatchdogTimeoutTicks;
        public final int tickBudgetMs;
        public final boolean enableUnlimitedOreFortune;
        public final boolean enableFortuneForPlacedOre;
        public final String parallelBudgetMode;
        public final int parallelSliceBudgetMs;
        /** 每次成功破坏一个方块消耗的饥饿值；可为 0 或负数（合法域 -40..40，语义见 HarvestExhaustionSettlement）。 */
        public final double harvestExhaustionPerBlock;
        public final boolean clientEnablePreviewRender;
        public final TunnelDirectionSource tunnelDirectionSource;
        public final boolean autoToolSwapEnabled;
        public final java.util.List<ToolSelector> autoToolPrioritySelectors;
        public final int clientPreviewMaxRadius;
        public final int clientPreviewMaxTargets;
        public final double clientPreviewAlphaFadeStartRadius;
        public final double clientPreviewAlphaFadeEndRadius;
        public final double clientPreviewAlphaStartValue;
        public final double clientPreviewAlphaEndValue;
        public final PreviewRenderBackend clientPreviewRenderBackend;
        public final double clientPreviewBarThickness;
        public final PreviewColorSource clientPreviewColorSource;
        public final int clientPreviewColorChain;
        public final int clientPreviewColorArea;
        public final int clientPreviewColorInteract;
        public final int clientPreviewColorSecondary;
        public final int clientPreviewColorRemote;
        public final int clientPreviewColorTruncated;
        public final PreviewDepthMode clientPreviewDepthMode;
        public final PreviewAnimationMode clientPreviewAnimation;
        public final int clientPreviewAnimationDurationMs;
        public final PreviewAnimationPhase clientPreviewAnimationPhase;
        public final PreviewFadeMode clientPreviewFadeMode;
        public final double clientPreviewFadeRefreshDistance;
        public final int clientPreviewFadeFallbackMs;
        public final double clientPreviewMinScreenWidthPx;
        public final double clientPreviewOutlineWidthPx;
        public final boolean clientPreviewFaceShading;
        public final boolean clientPreviewTruncationSignal;
        public final int clientPreviewMaxTargetsHardCap;
        public final PreviewLodMode clientPreviewLod;
        public final double clientPreviewLodMinAlpha;
        public final double clientPreviewOrderMinBrightness;
        public final double clientPreviewInteriorDim;
        public final boolean clientPreviewSuppressVanillaHighlight;
        public final boolean clientPreviewVersionedInputs;
        public final boolean clientPreviewPresentationOverlay;
        public final boolean clientPreviewExecutionProgress;
        public final boolean clientPreviewBackendDiagnostics;
        public final int clientPreviewRemoteTimeoutMs;
        public final ObjectGroupRuleSet objectGroups;

        ValidatedSnapshot(Map<String, Object> typed) {
            greeting = (String) typed.get("general.greeting");
            chainRadius = exactInt(typed, "general.chainRadius");
            chainMaxBlocks = exactInt(typed, "general.chainMaxBlocks");
            chainLoggingShellLayers = exactInt(typed, "general.chainLoggingShellLayers");
            cableReplaceMaxPerTick = exactInt(typed, "general.cableReplaceMaxPerTick");
            chainWatchdogTimeoutTicks = exactInt(typed, "general.chainWatchdogTimeoutTicks");
            tickBudgetMs = exactInt(typed, "general.tickBudgetMs");
            enableUnlimitedOreFortune = ((Boolean) typed.get("general.enableUnlimitedOreFortune")).booleanValue();
            enableFortuneForPlacedOre = ((Boolean) typed.get("general.enableFortuneForPlacedOre")).booleanValue();
            parallelBudgetMode = (String) typed.get("general.parallelBudgetMode");
            parallelSliceBudgetMs = exactInt(typed, "general.parallelSliceBudgetMs");
            harvestExhaustionPerBlock = number(typed, "general.harvestExhaustionPerBlock");
            clientEnablePreviewRender = ((Boolean) typed.get("client.clientEnablePreviewRender")).booleanValue();
            tunnelDirectionSource = (TunnelDirectionSource) typed.get("client.tunnelDirectionSource");
            autoToolSwapEnabled = ((Boolean) typed.get("client.autoToolSwapEnabled")).booleanValue();
            autoToolPrioritySelectors = immutableSelectors(typed.get("client.autoToolPrioritySelectors"));
            clientPreviewMaxRadius = exactInt(typed, "client.clientPreviewMaxRadius");
            clientPreviewMaxTargets = exactInt(typed, "client.clientPreviewMaxTargets");
            clientPreviewAlphaFadeStartRadius = number(typed, "client.clientPreviewAlphaFadeStartRadius");
            clientPreviewAlphaFadeEndRadius = number(typed, "client.clientPreviewAlphaFadeEndRadius");
            clientPreviewAlphaStartValue = number(typed, "client.clientPreviewAlphaStartValue");
            clientPreviewAlphaEndValue = number(typed, "client.clientPreviewAlphaEndValue");
            clientPreviewRenderBackend = PreviewRenderBackend.fromId(
                    (String) typed.get("client.clientPreviewRenderBackend"));
            clientPreviewBarThickness = number(typed, "client.clientPreviewBarThickness");
            clientPreviewColorSource = PreviewColorSource.fromId(
                    (String) typed.get("client.clientPreviewColorSource"));
            clientPreviewColorChain = exactInt(typed, "client.clientPreviewColorChain");
            clientPreviewColorArea = exactInt(typed, "client.clientPreviewColorArea");
            clientPreviewColorInteract = exactInt(typed, "client.clientPreviewColorInteract");
            clientPreviewColorSecondary = exactInt(typed, "client.clientPreviewColorSecondary");
            clientPreviewColorRemote = exactInt(typed, "client.clientPreviewColorRemote");
            clientPreviewColorTruncated = exactInt(typed, "client.clientPreviewColorTruncated");
            clientPreviewDepthMode = PreviewDepthMode.fromId((String) typed.get("client.clientPreviewDepthMode"));
            clientPreviewAnimation = PreviewAnimationMode.fromId((String) typed.get("client.clientPreviewAnimation"));
            clientPreviewAnimationDurationMs = exactInt(typed, "client.clientPreviewAnimationDurationMs");
            clientPreviewAnimationPhase = PreviewAnimationPhase.fromId(
                    (String) typed.get("client.clientPreviewAnimationPhase"));
            clientPreviewFadeMode = PreviewFadeMode.fromId((String) typed.get("client.clientPreviewFadeMode"));
            clientPreviewFadeRefreshDistance = number(typed, "client.clientPreviewFadeRefreshDistance");
            clientPreviewFadeFallbackMs = exactInt(typed, "client.clientPreviewFadeFallbackMs");
            clientPreviewMinScreenWidthPx = number(typed, "client.clientPreviewMinScreenWidthPx");
            clientPreviewOutlineWidthPx = number(typed, "client.clientPreviewOutlineWidthPx");
            clientPreviewFaceShading = ((Boolean) typed.get("client.clientPreviewFaceShading")).booleanValue();
            clientPreviewTruncationSignal = ((Boolean) typed.get("client.clientPreviewTruncationSignal"))
                    .booleanValue();
            clientPreviewMaxTargetsHardCap = exactInt(typed, "client.clientPreviewMaxTargetsHardCap");
            clientPreviewLod = PreviewLodMode.fromId((String) typed.get("client.clientPreviewLod"));
            clientPreviewLodMinAlpha = number(typed, "client.clientPreviewLodMinAlpha");
            clientPreviewOrderMinBrightness = number(typed, "client.clientPreviewOrderMinBrightness");
            clientPreviewInteriorDim = number(typed, "client.clientPreviewInteriorDim");
            clientPreviewSuppressVanillaHighlight = ((Boolean) typed
                    .get("client.clientPreviewSuppressVanillaHighlight")).booleanValue();
            clientPreviewVersionedInputs = ((Boolean) typed.get("client.clientPreviewVersionedInputs"))
                    .booleanValue();
            clientPreviewPresentationOverlay = ((Boolean) typed.get("client.clientPreviewPresentationOverlay"))
                    .booleanValue();
            clientPreviewExecutionProgress = ((Boolean) typed.get("client.clientPreviewExecutionProgress"))
                    .booleanValue();
            clientPreviewBackendDiagnostics = ((Boolean) typed.get("client.clientPreviewBackendDiagnostics"))
                    .booleanValue();
            clientPreviewRemoteTimeoutMs = exactInt(typed, "client.clientPreviewRemoteTimeoutMs");
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

}
