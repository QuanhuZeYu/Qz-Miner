package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;

public class ChainPreviewDrawPlanColorsTest {

    private static final int MASK = ChainPreviewDrawPlan.SEMANTIC_MASK_ALL;

    @Test
    public void baselineConstantQuantizesToBuiltinChainRgb() {
        int quantized = (Math.round(0.25F * 255.0F) << 16)
            | (Math.round(0.90F * 255.0F) << 8)
            | Math.round(1.00F * 255.0F);

        Assert.assertEquals(0x40E6FF, quantized);
        Assert.assertEquals(quantized, ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_CHAIN_RGB);
    }

    @Test
    public void builtinSourceUsesTheSixBuiltinSlots() {
        ChainPreviewDrawPlan.Visuals.Colors colors = ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
            "builtin", 0x112233, 0x445566, 0x778899, 0xAABBCC, 0xDDEEFF, 0x123456);

        Assert.assertEquals("builtin", colors.getSourceId());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_CHAIN_RGB, colors.getChain());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_AREA_RGB, colors.getArea());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_INTERACT_RGB, colors.getInteract());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_SUB_MODE_RGB, colors.getSecondary());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_REMOTE_RGB, colors.getRemote());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_TRUNCATED_RGB, colors.getTruncated());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN, colors);
    }

    @Test
    public void configSourceUsesConfiguredColorsBitwise() {
        ChainPreviewDrawPlan.Visuals.Colors colors = ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
            "config", 0x123456, 0xABCDEF, 0x010203, 0xE6FF40, 0x0F0F0F, 0xF0F0F0);

        Assert.assertEquals("config", colors.getSourceId());
        Assert.assertEquals(0x123456, colors.getChain());
        Assert.assertEquals(0xABCDEF, colors.getArea());
        Assert.assertEquals(0x010203, colors.getInteract());
        Assert.assertEquals(0xE6FF40, colors.getSecondary());
        Assert.assertEquals(0x0F0F0F, colors.getRemote());
        Assert.assertEquals(0xF0F0F0, colors.getTruncated());
    }

    @Test
    public void configColorsAreMaskedToRgbBits() {
        ChainPreviewDrawPlan.Visuals.Colors colors = ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
            "config", 0xFF123456, -1, 0x00ABCDEF, 0x7FFFFFFF, 0x01000000, 0x00FFFFFF);

        Assert.assertEquals(0x123456, colors.getChain());
        Assert.assertEquals(0xFFFFFF, colors.getArea());
        Assert.assertEquals(0xABCDEF, colors.getInteract());
        Assert.assertEquals(0xFFFFFF, colors.getSecondary());
        Assert.assertEquals(0x000000, colors.getRemote());
        Assert.assertEquals(0xFFFFFF, colors.getTruncated());
    }

    @Test
    public void nullOrUnknownSourceFallsBackToBuiltin() {
        Assert.assertEquals("builtin",
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig(null, 1, 2, 3, 4, 5, 6).getSourceId());
        Assert.assertEquals("builtin",
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig("bogus", 1, 2, 3, 4, 5, 6).getSourceId());
        Assert.assertEquals("builtin",
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig("", 1, 2, 3, 4, 5, 6).getSourceId());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_CHAIN_RGB,
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig("bogus", 1, 2, 3, 4, 5, 6).getChain());
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
            ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
                "config", 0x0A0B0C, 0x0D0E0F, 0x101112, 0x131415, 0x161718, 0x191A1B));

        ChainPreviewDrawPlan plan = ChainPreviewDrawPlan.derive(
            mesh, 0, 4, null, visuals, MASK, 0, 0, 0, 0L, 0L);

        Assert.assertEquals("config", plan.getColorSourceId());
        Assert.assertEquals(0x0A0B0C, plan.getColorChain());
        Assert.assertEquals(0x0D0E0F, plan.getColorArea());
        Assert.assertEquals(0x101112, plan.getColorInteract());
        Assert.assertEquals(0x131415, plan.getColorSecondary());
        Assert.assertEquals(0x161718, plan.getColorRemote());
        Assert.assertEquals(0x191A1B, plan.getColorTruncated());
    }
}
