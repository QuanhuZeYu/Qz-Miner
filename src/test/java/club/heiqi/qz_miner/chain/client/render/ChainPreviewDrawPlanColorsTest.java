package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

public class ChainPreviewDrawPlanColorsTest {

    private static final int MASK = ChainPreviewDrawPlan.SEMANTIC_MASK_ALL;

    @Test
    public void baselineConstantQuantizesToBuiltinRgb() {
        int quantized = (Math.round(0.25F * 255.0F) << 16)
            | (Math.round(0.90F * 255.0F) << 8)
            | Math.round(1.00F * 255.0F);

        Assert.assertEquals(0x40E6FF, quantized);
        Assert.assertEquals(quantized, ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_RGB);
    }

    @Test
    public void builtinSourceUsesExactBaselineConstantForAllCategories() {
        ChainPreviewDrawPlan.Visuals.Colors colors = ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
            "builtin", 0x112233, 0x445566, 0x778899, 0xAABBCC);

        Assert.assertEquals("builtin", colors.getSourceId());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_RGB, colors.getPrimary());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_RGB, colors.getSecondary());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_RGB, colors.getRemote());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_RGB, colors.getTruncated());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, colors);
    }

    @Test
    public void configSourceUsesConfiguredColorsBitwise() {
        ChainPreviewDrawPlan.Visuals.Colors colors = ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
            "config", 0x123456, 0xABCDEF, 0x010203, 0xE6FF40);

        Assert.assertEquals("config", colors.getSourceId());
        Assert.assertEquals(0x123456, colors.getPrimary());
        Assert.assertEquals(0xABCDEF, colors.getSecondary());
        Assert.assertEquals(0x010203, colors.getRemote());
        Assert.assertEquals(0xE6FF40, colors.getTruncated());
    }

    @Test
    public void configColorsAreMaskedToRgbBits() {
        ChainPreviewDrawPlan.Visuals.Colors colors = ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
            "config", 0xFF123456, -1, 0x00ABCDEF, 0x7FFFFFFF);

        Assert.assertEquals(0x123456, colors.getPrimary());
        Assert.assertEquals(0xFFFFFF, colors.getSecondary());
        Assert.assertEquals(0xABCDEF, colors.getRemote());
        Assert.assertEquals(0xFFFFFF, colors.getTruncated());
    }

    @Test
    public void nullOrUnknownSourceFallsBackToBuiltin() {
        Assert.assertEquals("builtin",
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig(null, 1, 2, 3, 4).getSourceId());
        Assert.assertEquals("builtin",
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig("bogus", 1, 2, 3, 4).getSourceId());
        Assert.assertEquals("builtin",
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig("", 1, 2, 3, 4).getSourceId());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_RGB,
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig("bogus", 1, 2, 3, 4).getPrimary());
    }

    @Test
    public void nullColorsConvergeToBuiltin() {
        ChainPreviewDrawPlan.Visuals visuals = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F, null);

        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, visuals.getColors());
    }

    @Test
    public void planAccessorsDelegateToMappedColors() {
        ChainPreviewMesh mesh = new ChainPreviewMesh(new float[6], new float[8], new int[4], 1);
        ChainPreviewDrawPlan.Visuals visuals = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
            ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F,
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig("config", 0x0A0B0C, 0x0D0E0F, 0x101112, 0x131415));

        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh, 0, 4, null, visuals, MASK, 0, 0, 0, 0L, 0L);

        Assert.assertEquals("config", plan.getColorSourceId());
        Assert.assertEquals(0x0A0B0C, plan.getColorPrimary());
        Assert.assertEquals(0x0D0E0F, plan.getColorSecondary());
        Assert.assertEquals(0x101112, plan.getColorRemote());
        Assert.assertEquals(0x131415, plan.getColorTruncated());
    }
}
