package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;

/** 视觉设置快照（§H 唯一配置读取面）的不可变、兜底与曲线同源契约。 */
public class ChainPreviewVisualSettingsTest {

    @Test
    public void fromConfigIsStableAndEqualsIsValueBased() {
        ChainPreviewVisualSettings settings = ChainPreviewVisualSettings.fromConfig();
        ChainPreviewVisualSettings again = ChainPreviewVisualSettings.fromConfig();

        Assert.assertNotNull(settings);
        Assert.assertEquals("值相等即观感无变化", settings, again);
        Assert.assertEquals(settings.hashCode(), again.hashCode());
        Assert.assertEquals(Config.clientPreviewBarThickness, settings.getBarThickness(), 1.0e-6D);
        Assert.assertEquals(0.045F, settings.getBarThickness(), 1.0e-6F);
        Assert.assertEquals(Config.clientPreviewMinScreenWidthPx, settings.getMinScreenWidthPx(), 1.0e-6D);
        Assert.assertEquals(Config.clientPreviewOutlineWidthPx, settings.getOutlineWidthPx(), 1.0e-6D);
        Assert.assertNotNull(settings.getDepthModeId());
        Assert.assertNotNull(settings.getAnimationId());
        Assert.assertNotNull(settings.getAnimationPhaseId());
        Assert.assertNotNull(settings.getFadeModeId());
        Assert.assertNotNull(settings.getColorSourceId());
        Assert.assertNotNull(settings.getLodId());
        Assert.assertEquals(
            Config.clientPreviewMaxTargetsHardCap, settings.getMaxTargetsHardCap());
        Assert.assertEquals(
            (float) Config.clientPreviewAlphaStartValue, settings.getAlphaStartValue(), 1.0e-6F);
        Assert.assertEquals(Config.clientPreviewRenderBackend.id(), settings.getRenderBackendId());
    }

    @Test
    public void explicitConstructionClampsOutOfRangeAndDoesNotThrow() {
        ChainPreviewVisualSettings settings = new ChainPreviewVisualSettings(
            Float.NaN,
            Float.POSITIVE_INFINITY,
            null,
            null,
            null,
            null,
            null,
            null,
           0x1FFFFFF,
            -1,
            0x40E6FF,
            0x40E6FF,
            -10,
            Float.NaN,
            0,
            -5.0F,
            -1.0F,
            2.0F,
            Float.NaN,
            null,
            2.0F,
            true,
            0);

        Assert.assertEquals(0.045F, settings.getBarThickness(), 1.0e-6F);
        Assert.assertEquals("非法 minScreenWidthPx 回落基线档 0.0", 0.0F, settings.getMinScreenWidthPx(), 1.0e-6F);
        Assert.assertEquals("未显式传入描边宽度时必须为基线档 0.0（关闭）",
            0.0F, settings.getOutlineWidthPx(), 1.0e-6F);
        Assert.assertEquals(0, settings.getAnimationDurationMs());
        Assert.assertEquals(0.5F, settings.getFadeRefreshDistance(), 1.0e-6F);
        Assert.assertEquals(250, settings.getFadeFallbackMs());
        Assert.assertEquals(1.0F, settings.getLodMinAlpha(), 1.0e-6F);
        Assert.assertEquals(4096, settings.getMaxTargetsHardCap());
        Assert.assertEquals(0xFFFFFF, settings.getColorPrimary());
        Assert.assertEquals("负色值按 24 位掩码收窄", 0xFFFFFF, settings.getColorSecondary());
        Assert.assertEquals(0.0F, settings.getAlphaFadeStartRadius(), 1.0e-6F);
        Assert.assertEquals("fadeEnd 必须严格大于 fadeStart", 0.001F, settings.getAlphaFadeEndRadius(), 1.0e-6F);
        Assert.assertEquals(1.0F, settings.getAlphaStartValue(), 1.0e-6F);
        Assert.assertEquals(0.15F, settings.getAlphaEndValue(), 1.0e-6F);
        Assert.assertNotNull(settings.getDepthModeId());
        Assert.assertNotNull(settings.getLodId());
        Assert.assertEquals("非法后端 id 回落默认 auto", "auto", settings.getRenderBackendId());
    }

    @Test
    public void nanAndOutOfRangeMinScreenWidthMatchesDrawPlanSanitized() {
        Assert.assertEquals("NaN 必须回落基线 0.0",
            0.0F, settingsWithMinScreenWidth(Float.NaN).getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals("负值收窄到 0.0",
            0.0F, settingsWithMinScreenWidth(-5.0F).getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals("越界上界收窄到 8.0",
            8.0F, settingsWithMinScreenWidth(100.0F).getMinScreenWidthPx(), 0.0F);
        Assert.assertEquals("settings 与 draw plan sanitized 必须同口径（NaN）",
            drawPlanMinScreenWidth(Float.NaN),
            settingsWithMinScreenWidth(Float.NaN).getMinScreenWidthPx(),
            0.0F);
        Assert.assertEquals("settings 与 draw plan sanitized 必须同口径（越界）",
            drawPlanMinScreenWidth(100.0F),
            settingsWithMinScreenWidth(100.0F).getMinScreenWidthPx(),
            0.0F);
    }

    /** 描边宽度与 min-width 同口径：NaN / 负值回落 0，越界收窄到 8，且与 draw plan sanitized 一致。 */
    @Test
    public void nanAndOutOfRangeOutlineWidthMatchesDrawPlanSanitized() {
        Assert.assertEquals("NaN 必须回落基线 0.0",
            0.0F, settingsWithOutlineWidth(Float.NaN).getOutlineWidthPx(), 0.0F);
        Assert.assertEquals("负值收窄到 0.0",
            0.0F, settingsWithOutlineWidth(-5.0F).getOutlineWidthPx(), 0.0F);
        Assert.assertEquals("0 = 关闭描边必须原样保留",
            0.0F, settingsWithOutlineWidth(0.0F).getOutlineWidthPx(), 0.0F);
        Assert.assertEquals("越界上界收窄到 8.0",
            8.0F, settingsWithOutlineWidth(100.0F).getOutlineWidthPx(), 0.0F);
        Assert.assertEquals("settings 与 draw plan sanitized 必须同口径（NaN）",
            drawPlanOutlineWidth(Float.NaN),
            settingsWithOutlineWidth(Float.NaN).getOutlineWidthPx(),
            0.0F);
        Assert.assertEquals("settings 与 draw plan sanitized 必须同口径（越界）",
            drawPlanOutlineWidth(100.0F),
            settingsWithOutlineWidth(100.0F).getOutlineWidthPx(),
            0.0F);
    }

    @Test
    public void invalidRenderBackendIdFallsBackToDefaultAuto() {
        Assert.assertEquals("auto", settingsWithBackendId(null).getRenderBackendId());
        Assert.assertEquals("auto", settingsWithBackendId("bogus").getRenderBackendId());
        Assert.assertEquals("legacy", settingsWithBackendId("legacy").getRenderBackendId());
        Assert.assertEquals("shader", settingsWithBackendId("shader").getRenderBackendId());
    }

    @Test
    public void currentSnapshotUsesPublishedInstanceAndPublishRejectsNull() {
        ChainPreviewVisualSettings published = new ChainPreviewVisualSettings(
            0.1F, 2.0F, "xray", "off", "order", "timer", "builtin", "legacy",
            0x112233, 0x445566, 0x778899, 0xAABBCC, 120, 0.5F, 250, 2.0F, 6.0F, 0.78F, 0.15F,
            "off", 0.05F, true, 4096);
        ChainPreviewVisualSettings before = ChainPreviewVisualSettings.current();
        try {
            Assert.assertNotNull("current() 必须初始化出非 null 快照", before);
            ChainPreviewVisualSettings.publish(published);
            Assert.assertSame("current() 必须是零分配的引用读取",
                published, ChainPreviewVisualSettings.current());
            Assert.assertTrue(ChainPreviewVisualSettings.current().isTruncationSignalEnabled());
            ChainPreviewVisualSettings.publish(null);
            Assert.assertSame("null 发布不得覆盖当前快照",
                published, ChainPreviewVisualSettings.current());
        } finally {
            ChainPreviewVisualSettings.publish(before);
        }
    }

    private static ChainPreviewVisualSettings settingsWithMinScreenWidth(float minScreenWidthPx) {
        return new ChainPreviewVisualSettings(
            0.045F, minScreenWidthPx, "xray", "off", "order", "timer", "builtin", "auto",
            0x40E6FF, 0x40E6FF, 0x40E6FF, 0x40E6FF, 120, 0.5F, 250, 2.0F, 6.0F, 0.78F, 0.15F,
            "off", 0.05F, false, 4096);
    }

    private static ChainPreviewVisualSettings settingsWithOutlineWidth(float outlineWidthPx) {
        return new ChainPreviewVisualSettings(
            0.045F, 0.0F, "outline", "off", "order", "timer", "builtin", "shader",
            0x40E6FF, 0x40E6FF, 0x40E6FF, 0x40E6FF, 120, 0.5F, 250, 2.0F, 6.0F, 0.78F, 0.15F,
            "off", 0.05F, false, 4096, false, outlineWidthPx);
    }

    private static float drawPlanOutlineWidth(float outlineWidthPx) {
        return ChainPreviewDrawPlan.Visuals.BASELINE.withOutlinePass(true, outlineWidthPx).getOutlineWidthPx();
    }

    private static ChainPreviewVisualSettings settingsWithBackendId(String renderBackendId) {
        return new ChainPreviewVisualSettings(
            0.045F, 0.0F, "xray", "off", "order", "timer", "builtin", renderBackendId,
            0x40E6FF, 0x40E6FF, 0x40E6FF, 0x40E6FF, 120, 0.5F, 250, 2.0F, 6.0F, 0.78F, 0.15F,
            "off", 0.05F, false, 4096);
    }

    private static float drawPlanMinScreenWidth(float minScreenWidthPx) {
        ChainPreviewDrawPlan.Visuals raw = new ChainPreviewDrawPlan.Visuals(
            0.045F, minScreenWidthPx, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F, ChainPreviewDrawPlan.DepthChannel.XRAY);
        return ChainPreviewDrawPlan.derive(
            ChainPreviewMesh.EMPTY,
            0, 0, null, raw, ChainPreviewDrawPlan.SEMANTIC_MASK_ALL, 0, 0, 0, 0L, 0L).getMinScreenWidthPx();
    }

    @Test
    public void alphaCurveMatchesLegacyCpuFormAtSampleDistances() {
        float fadeStart = 2.0F;
        float fadeEnd = 6.0F;
        float maxAlpha = 0.78F;
        float minAlpha = 0.15F;
        ChainPreviewMeshBuilder.VisualParameters legacy = new ChainPreviewMeshBuilder.VisualParameters(
            0.0D, 0.0D, 0.0D, fadeStart, fadeEnd, maxAlpha, minAlpha, 0.045F);

        double[] samples = {0.0D, 1.99D, 2.0D, 3.0D, 4.0D, 5.5D, 6.0D, 12.0D};
        for (double distance : samples) {
            float expected = legacy.alphaFor(0.0D, 0.0D, distance);
            Assert.assertEquals(
                "distance=" + distance,
                expected,
                ChainPreviewVisualSettings.alphaFor(distance, fadeStart, fadeEnd, maxAlpha, minAlpha),
                1.0e-6F);
        }

        Assert.assertEquals(maxAlpha,
            ChainPreviewVisualSettings.alphaFor(1.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0e-6F);
        Assert.assertEquals(minAlpha,
            ChainPreviewVisualSettings.alphaFor(9.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0e-6F);
        Assert.assertEquals(0.6225F,
            ChainPreviewVisualSettings.alphaFor(4.0D, fadeStart, fadeEnd, maxAlpha, minAlpha), 1.0e-6F);
    }
}
