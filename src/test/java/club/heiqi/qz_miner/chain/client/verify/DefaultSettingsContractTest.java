package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.client.ChainPreviewVisualSettings;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.config.PreviewAnimationMode;
import club.heiqi.qz_miner.config.PreviewAnimationPhase;
import club.heiqi.qz_miner.config.PreviewColorSource;
import club.heiqi.qz_miner.config.PreviewDepthMode;
import club.heiqi.qz_miner.config.PreviewFadeMode;
import club.heiqi.qz_miner.config.PreviewLodMode;
import club.heiqi.qz_miner.config.PreviewRenderBackend;
import club.heiqi.qz_miner.config.QzMinerConfigDefaults;

/**
 * T7 默认档与回退组合独立契约探针（接口冻结 §E/§H + Lead 裁定 3）。
 *
 * <p>Lead 裁定：默认值不得超前于实现（animation=off、minScreenWidthPx=0.0、fadeMode=timer、
 * truncationSignal=false、versionedInputs=false）。回退到今天的五开关组合与默认映射必须一致；
 * 唯一差异是 renderBackend（配置项，不进入视觉快照）。</p>
 */
public class DefaultSettingsContractTest {

    @Test
    public void frozenDefaultsMatchInterfaceFreezeAndDoNotExceedImplementation() {
        Assert.assertEquals("auto", QzMinerConfigDefaults.CLIENT_PREVIEW_RENDER_BACKEND);
        Assert.assertEquals("auto", PreviewRenderBackend.defaultValue().id());
        Assert.assertEquals(0.045D, QzMinerConfigDefaults.CLIENT_PREVIEW_BAR_THICKNESS, 0.0D);
        Assert.assertEquals("builtin", QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SOURCE);
        Assert.assertEquals("builtin", PreviewColorSource.defaultValue().id());
        Assert.assertEquals(0x40E6FF, QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_PRIMARY);
        Assert.assertEquals(0x40E6FF, QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SECONDARY);
        Assert.assertEquals(0x40E6FF, QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_REMOTE);
        Assert.assertEquals(0x40E6FF, QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_TRUNCATED);
        Assert.assertEquals("xray", QzMinerConfigDefaults.CLIENT_PREVIEW_DEPTH_MODE);
        Assert.assertEquals("xray", PreviewDepthMode.defaultValue().id());
        Assert.assertEquals("off", QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION);
        Assert.assertEquals("off", PreviewAnimationMode.defaultValue().id());
        Assert.assertEquals(120, QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_DURATION_MS);
        Assert.assertEquals("order", QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_PHASE);
        Assert.assertEquals("order", PreviewAnimationPhase.defaultValue().id());
        Assert.assertEquals("timer", QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_MODE);
        Assert.assertEquals("timer", PreviewFadeMode.defaultValue().id());
        Assert.assertEquals(0.5D, QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_REFRESH_DISTANCE, 0.0D);
        Assert.assertEquals(250, QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_FALLBACK_MS);
        Assert.assertEquals(0.0D, QzMinerConfigDefaults.CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX, 0.0D);
        Assert.assertFalse(QzMinerConfigDefaults.CLIENT_PREVIEW_TRUNCATION_SIGNAL);
        Assert.assertEquals(4096, QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP);
        Assert.assertEquals("off", QzMinerConfigDefaults.CLIENT_PREVIEW_LOD);
        Assert.assertEquals("off", PreviewLodMode.defaultValue().id());
        Assert.assertEquals(0.05D, QzMinerConfigDefaults.CLIENT_PREVIEW_LOD_MIN_ALPHA, 0.0D);
        Assert.assertFalse(QzMinerConfigDefaults.CLIENT_PREVIEW_SUPPRESS_VANILLA_HIGHLIGHT);
        Assert.assertFalse(QzMinerConfigDefaults.CLIENT_PREVIEW_VERSIONED_INPUTS);
        Assert.assertFalse(QzMinerConfigDefaults.CLIENT_PREVIEW_PRESENTATION_OVERLAY);
        Assert.assertEquals(5000, QzMinerConfigDefaults.CLIENT_PREVIEW_REMOTE_TIMEOUT_MS);
        Assert.assertEquals(2.0D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS, 0.0D);
        Assert.assertEquals(6.0D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS, 0.0D);
        Assert.assertEquals(0.78D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE, 0.0D);
        Assert.assertEquals(0.15D, QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE, 0.0D);
        Assert.assertEquals(16, QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS);
        Assert.assertEquals(1024, QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS);
        Assert.assertTrue(QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER);
    }

    @Test
    public void configSnapshotMapsEveryFrozenDefault() {
        Snapshot snapshot = new Snapshot();
        try {
            applyDefaults();
            ChainPreviewVisualSettings settings = ChainPreviewVisualSettings.fromConfig();
            Assert.assertEquals("auto", settings.getRenderBackendId());
            Assert.assertEquals(0.045F, settings.getBarThickness(), 0.0000001F);
            Assert.assertEquals(0.0F, settings.getMinScreenWidthPx(), 0.0F);
            Assert.assertEquals("xray", settings.getDepthModeId());
            Assert.assertEquals("off", settings.getAnimationId());
            Assert.assertEquals("order", settings.getAnimationPhaseId());
            Assert.assertEquals("timer", settings.getFadeModeId());
            Assert.assertEquals("builtin", settings.getColorSourceId());
            Assert.assertEquals(0x40E6FF, settings.getColorPrimary());
            Assert.assertEquals(0x40E6FF, settings.getColorSecondary());
            Assert.assertEquals(0x40E6FF, settings.getColorRemote());
            Assert.assertEquals(0x40E6FF, settings.getColorTruncated());
            Assert.assertEquals(120, settings.getAnimationDurationMs());
            Assert.assertEquals(0.5F, settings.getFadeRefreshDistance(), 0.0000001F);
            Assert.assertEquals(250, settings.getFadeFallbackMs());
            Assert.assertEquals(2.0F, settings.getAlphaFadeStartRadius(), 0.0F);
            Assert.assertEquals(6.0F, settings.getAlphaFadeEndRadius(), 0.0F);
            Assert.assertEquals(0.78F, settings.getAlphaStartValue(), 0.0F);
            Assert.assertEquals(0.15F, settings.getAlphaEndValue(), 0.0F);
            Assert.assertEquals("off", settings.getLodId());
            Assert.assertEquals(0.05F, settings.getLodMinAlpha(), 0.0000001F);
            Assert.assertFalse(settings.isTruncationSignalEnabled());
            Assert.assertEquals(4096, settings.getMaxTargetsHardCap());
        } finally {
            snapshot.restore();
        }
    }

    @Test
    public void fallbackCombinationProducesSameVisualSnapshotAsDefaults() {
        Snapshot snapshot = new Snapshot();
        try {
            applyDefaults();
            ChainPreviewVisualSettings defaults = ChainPreviewVisualSettings.fromConfig();

            // 回退到今天：renderBackend=legacy + fadeMode=timer + animation=off
            // + minScreenWidthPx=0 + truncationSignal=off
            Config.clientPreviewRenderBackend = PreviewRenderBackend.LEGACY;
            Config.clientPreviewFadeMode = PreviewFadeMode.TIMER;
            Config.clientPreviewAnimation = PreviewAnimationMode.OFF;
            Config.clientPreviewMinScreenWidthPx = 0.0D;
            Config.clientPreviewTruncationSignal = false;
            ChainPreviewVisualSettings fallback = ChainPreviewVisualSettings.fromConfig();

            // 快照现已包含 renderBackendId（session-core 扩展），故唯一允许的差异就是它：
            // 默认 auto vs 回退 legacy；其余视觉字段必须逐项相等。
            Assert.assertEquals("auto", defaults.getRenderBackendId());
            Assert.assertEquals("legacy", fallback.getRenderBackendId());
            assertSameVisualFields(defaults, fallback);
            Assert.assertFalse(
                "除 renderBackend 外不得再有差异", defaults.equals(fallback));
            Assert.assertEquals(0.0F, fallback.getMinScreenWidthPx(), 0.0F);
            Assert.assertEquals("timer", fallback.getFadeModeId());
            Assert.assertEquals("off", fallback.getAnimationId());
            Assert.assertFalse(fallback.isTruncationSignalEnabled());
            Assert.assertEquals("builtin", fallback.getColorSourceId());
            Assert.assertEquals("xray", fallback.getDepthModeId());
            Assert.assertEquals(0.045F, fallback.getBarThickness(), 0.0000001F);
            Assert.assertEquals(2.0F, fallback.getAlphaFadeStartRadius(), 0.0F);
            Assert.assertEquals(6.0F, fallback.getAlphaFadeEndRadius(), 0.0F);
            Assert.assertEquals(0.78F, fallback.getAlphaStartValue(), 0.0F);
            Assert.assertEquals(0.15F, fallback.getAlphaEndValue(), 0.0F);
        } finally {
            snapshot.restore();
        }
    }

    @Test
    public void unknownEnumAndNaNInputsFallBackWithoutThrowing() {
        Snapshot snapshot = new Snapshot();
        try {
            applyDefaults();
            Config.clientPreviewDepthMode = null;
            Config.clientPreviewAnimation = null;
            Config.clientPreviewFadeMode = null;
            Config.clientPreviewColorSource = null;
            Config.clientPreviewLod = null;
            Config.clientPreviewBarThickness = Double.NaN;
            Config.clientPreviewMinScreenWidthPx = Double.NaN;
            Config.clientPreviewAlphaStartValue = Double.NaN;
            Config.clientPreviewAlphaEndValue = Double.NaN;
            Config.clientPreviewLodMinAlpha = Double.NaN;
            Config.clientPreviewMaxTargetsHardCap = 0;
            ChainPreviewVisualSettings settings = ChainPreviewVisualSettings.fromConfig();
            Assert.assertEquals("xray", settings.getDepthModeId());
            Assert.assertEquals("off", settings.getAnimationId());
            Assert.assertEquals("timer", settings.getFadeModeId());
            Assert.assertEquals("builtin", settings.getColorSourceId());
            Assert.assertEquals("off", settings.getLodId());
            Assert.assertEquals(0.045F, settings.getBarThickness(), 0.0000001F);
            // Lead 裁定：NaN 兜底必须与 §E 新默认及 draw plan sanitized 一致，均为 0.0（原本为旧默认 1.0）。
            Assert.assertEquals(
                "NaN → minScreenWidthPx 兜底必须为 0.0（与 draw plan sanitized 一致）",
                0.0F,
                settings.getMinScreenWidthPx(),
                0.0F);
            Assert.assertEquals(
                "draw plan 层 NaN 兜底同样必须是 0.0（两层一致）",
                0.0F,
                new ChainPreviewDrawPlan.Visuals(
                    0.045F, Float.NaN, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
                    ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F).sanitized().getMinScreenWidthPx(),
                0.0F);
            Assert.assertEquals(0.78F, settings.getAlphaStartValue(), 0.0F);
            Assert.assertEquals(0.15F, settings.getAlphaEndValue(), 0.0F);
            Assert.assertEquals(0.05F, settings.getLodMinAlpha(), 0.0000001F);
            Assert.assertEquals(4096, settings.getMaxTargetsHardCap());
        } finally {
            snapshot.restore();
        }
    }

    private static void applyDefaults() {
        Config.clientPreviewRenderBackend =
            PreviewRenderBackend.fromId(QzMinerConfigDefaults.CLIENT_PREVIEW_RENDER_BACKEND);
        Config.clientPreviewBarThickness = QzMinerConfigDefaults.CLIENT_PREVIEW_BAR_THICKNESS;
        Config.clientPreviewColorSource =
            PreviewColorSource.fromId(QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SOURCE);
        Config.clientPreviewColorPrimary = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_PRIMARY;
        Config.clientPreviewColorSecondary = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_SECONDARY;
        Config.clientPreviewColorRemote = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_REMOTE;
        Config.clientPreviewColorTruncated = QzMinerConfigDefaults.CLIENT_PREVIEW_COLOR_TRUNCATED;
        Config.clientPreviewDepthMode = PreviewDepthMode.fromId(QzMinerConfigDefaults.CLIENT_PREVIEW_DEPTH_MODE);
        Config.clientPreviewAnimation = PreviewAnimationMode.fromId(QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION);
        Config.clientPreviewAnimationDurationMs = QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_DURATION_MS;
        Config.clientPreviewAnimationPhase =
            PreviewAnimationPhase.fromId(QzMinerConfigDefaults.CLIENT_PREVIEW_ANIMATION_PHASE);
        Config.clientPreviewFadeMode = PreviewFadeMode.fromId(QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_MODE);
        Config.clientPreviewFadeRefreshDistance = QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_REFRESH_DISTANCE;
        Config.clientPreviewFadeFallbackMs = QzMinerConfigDefaults.CLIENT_PREVIEW_FADE_FALLBACK_MS;
        Config.clientPreviewMinScreenWidthPx = QzMinerConfigDefaults.CLIENT_PREVIEW_MIN_SCREEN_WIDTH_PX;
        Config.clientPreviewTruncationSignal = QzMinerConfigDefaults.CLIENT_PREVIEW_TRUNCATION_SIGNAL;
        Config.clientPreviewMaxTargetsHardCap = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS_HARD_CAP;
        Config.clientPreviewLod = PreviewLodMode.fromId(QzMinerConfigDefaults.CLIENT_PREVIEW_LOD);
        Config.clientPreviewLodMinAlpha = QzMinerConfigDefaults.CLIENT_PREVIEW_LOD_MIN_ALPHA;
        Config.clientPreviewAlphaFadeStartRadius =
            QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS;
        Config.clientPreviewAlphaFadeEndRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS;
        Config.clientPreviewAlphaStartValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE;
        Config.clientPreviewAlphaEndValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE;
    }

    /** 逐项比较除 renderBackendId 之外的视觉字段。 */
    private static void assertSameVisualFields(
            ChainPreviewVisualSettings expected, ChainPreviewVisualSettings actual) {
        Assert.assertEquals(expected.getBarThickness(), actual.getBarThickness(), 0.0F);
        Assert.assertEquals(expected.getMinScreenWidthPx(), actual.getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals(expected.getDepthModeId(), actual.getDepthModeId());
        Assert.assertEquals(expected.getAnimationId(), actual.getAnimationId());
        Assert.assertEquals(expected.getAnimationPhaseId(), actual.getAnimationPhaseId());
        Assert.assertEquals(expected.getFadeModeId(), actual.getFadeModeId());
        Assert.assertEquals(expected.getColorSourceId(), actual.getColorSourceId());
        Assert.assertEquals(expected.getColorPrimary(), actual.getColorPrimary());
        Assert.assertEquals(expected.getColorSecondary(), actual.getColorSecondary());
        Assert.assertEquals(expected.getColorRemote(), actual.getColorRemote());
        Assert.assertEquals(expected.getColorTruncated(), actual.getColorTruncated());
        Assert.assertEquals(expected.getAnimationDurationMs(), actual.getAnimationDurationMs());
        Assert.assertEquals(expected.getFadeRefreshDistance(), actual.getFadeRefreshDistance(), 0.0F);
        Assert.assertEquals(expected.getFadeFallbackMs(), actual.getFadeFallbackMs());
        Assert.assertEquals(expected.getAlphaFadeStartRadius(), actual.getAlphaFadeStartRadius(), 0.0F);
        Assert.assertEquals(expected.getAlphaFadeEndRadius(), actual.getAlphaFadeEndRadius(), 0.0F);
        Assert.assertEquals(expected.getAlphaStartValue(), actual.getAlphaStartValue(), 0.0F);
        Assert.assertEquals(expected.getAlphaEndValue(), actual.getAlphaEndValue(), 0.0F);
        Assert.assertEquals(expected.getLodId(), actual.getLodId());
        Assert.assertEquals(expected.getLodMinAlpha(), actual.getLodMinAlpha(), 0.0F);
        Assert.assertEquals(
            expected.isTruncationSignalEnabled(), actual.isTruncationSignalEnabled());
        Assert.assertEquals(expected.getMaxTargetsHardCap(), actual.getMaxTargetsHardCap());
    }

    /** 保护同 JVM 内其它配置测试：本类只改这些字段，用后原值恢复。 */
    private static final class Snapshot {

        private final PreviewRenderBackend renderBackend = Config.clientPreviewRenderBackend;
        private final double barThickness = Config.clientPreviewBarThickness;
        private final PreviewColorSource colorSource = Config.clientPreviewColorSource;
        private final int colorPrimary = Config.clientPreviewColorPrimary;
        private final int colorSecondary = Config.clientPreviewColorSecondary;
        private final int colorRemote = Config.clientPreviewColorRemote;
        private final int colorTruncated = Config.clientPreviewColorTruncated;
        private final PreviewDepthMode depthMode = Config.clientPreviewDepthMode;
        private final PreviewAnimationMode animation = Config.clientPreviewAnimation;
        private final int animationDurationMs = Config.clientPreviewAnimationDurationMs;
        private final PreviewAnimationPhase animationPhase = Config.clientPreviewAnimationPhase;
        private final PreviewFadeMode fadeMode = Config.clientPreviewFadeMode;
        private final double fadeRefreshDistance = Config.clientPreviewFadeRefreshDistance;
        private final int fadeFallbackMs = Config.clientPreviewFadeFallbackMs;
        private final double minScreenWidthPx = Config.clientPreviewMinScreenWidthPx;
        private final boolean truncationSignal = Config.clientPreviewTruncationSignal;
        private final int maxTargetsHardCap = Config.clientPreviewMaxTargetsHardCap;
        private final PreviewLodMode lod = Config.clientPreviewLod;
        private final double lodMinAlpha = Config.clientPreviewLodMinAlpha;
        private final double alphaFadeStart = Config.clientPreviewAlphaFadeStartRadius;
        private final double alphaFadeEnd = Config.clientPreviewAlphaFadeEndRadius;
        private final double alphaStart = Config.clientPreviewAlphaStartValue;
        private final double alphaEnd = Config.clientPreviewAlphaEndValue;

        private void restore() {
            Config.clientPreviewRenderBackend = renderBackend;
            Config.clientPreviewBarThickness = barThickness;
            Config.clientPreviewColorSource = colorSource;
            Config.clientPreviewColorPrimary = colorPrimary;
            Config.clientPreviewColorSecondary = colorSecondary;
            Config.clientPreviewColorRemote = colorRemote;
            Config.clientPreviewColorTruncated = colorTruncated;
            Config.clientPreviewDepthMode = depthMode;
            Config.clientPreviewAnimation = animation;
            Config.clientPreviewAnimationDurationMs = animationDurationMs;
            Config.clientPreviewAnimationPhase = animationPhase;
            Config.clientPreviewFadeMode = fadeMode;
            Config.clientPreviewFadeRefreshDistance = fadeRefreshDistance;
            Config.clientPreviewFadeFallbackMs = fadeFallbackMs;
            Config.clientPreviewMinScreenWidthPx = minScreenWidthPx;
            Config.clientPreviewTruncationSignal = truncationSignal;
            Config.clientPreviewMaxTargetsHardCap = maxTargetsHardCap;
            Config.clientPreviewLod = lod;
            Config.clientPreviewLodMinAlpha = lodMinAlpha;
            Config.clientPreviewAlphaFadeStartRadius = alphaFadeStart;
            Config.clientPreviewAlphaFadeEndRadius = alphaFadeEnd;
            Config.clientPreviewAlphaStartValue = alphaStart;
            Config.clientPreviewAlphaEndValue = alphaEnd;
        }
    }
}
