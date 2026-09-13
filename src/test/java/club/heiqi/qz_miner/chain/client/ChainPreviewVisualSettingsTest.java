package club.heiqi.qz_miner.chain.client;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.Config;

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
        Assert.assertEquals("非法 minScreenWidthPx 回落默认 1.0", 1.0F, settings.getMinScreenWidthPx(), 1.0e-6F);
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
