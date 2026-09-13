package club.heiqi.qz_miner.chain.client.verify;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewMesh;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder;
import club.heiqi.qz_miner.chain.client.ChainPreviewMeshBuilder.VisualParameters;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan.Visuals.Colors;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewDrawPlan.DepthChannel;
import club.heiqi.qz_miner.chain.client.render.ChainPreviewShaderMath;

/**
 * T17 颜色语义与来源解耦独立探针（T16c + 接口冻结 §D）。
 *
 * <p>独立口径：builtin 档对任意类别都必须返回精确基线常量 (0.25, 0.9, 1.0)（逐位）；
 * config 档按类别选四色并按 8bit 量化（/255）；类别 4/5/255/未知一律兜底主色；
 * 颜色面 Colors 归一化来源 id 并按 0xFFFFFF 收窄通道；plan 透传颜色面。</p>
 */
public class SemanticColorContractTest {

    private static final int PRIMARY = 0x112233;
    private static final int SECONDARY = 0x445566;
    private static final int REMOTE = 0x778899;
    private static final int TRUNCATED = 0xAABBCC;

    @Test
    public void builtinPaletteIsExactBaselineForEveryClass() {
        int[] classes = {0, 1, 2, 3, 4, 5, 255, -1, 99, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int semanticClass : classes) {
            float[] rgb = ChainPreviewShaderMath.builtinColorRgb(semanticClass);
            Assert.assertEquals("class=" + semanticClass + " R", Float.floatToIntBits(0.25F),
                Float.floatToIntBits(rgb[0]));
            Assert.assertEquals("class=" + semanticClass + " G", Float.floatToIntBits(0.9F),
                Float.floatToIntBits(rgb[1]));
            Assert.assertEquals("class=" + semanticClass + " B", Float.floatToIntBits(1.0F),
                Float.floatToIntBits(rgb[2]));
        }
    }

    @Test
    public void configPaletteMapsClassToFourColorsExactly() {
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(0, PRIMARY, SECONDARY, REMOTE, TRUNCATED), PRIMARY);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(1, PRIMARY, SECONDARY, REMOTE, TRUNCATED), SECONDARY);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(2, PRIMARY, SECONDARY, REMOTE, TRUNCATED), REMOTE);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(3, PRIMARY, SECONDARY, REMOTE, TRUNCATED), TRUNCATED);
        int[] fallbackClasses = {4, 5, 6, 99, 254, 255, -1, Integer.MAX_VALUE};
        for (int semanticClass : fallbackClasses) {
            assertRgb(
                "class=" + semanticClass + " 必须兜底主色",
                ChainPreviewShaderMath.semanticColorRgb(
                    semanticClass, PRIMARY, SECONDARY, REMOTE, TRUNCATED),
                PRIMARY);
        }
    }

    @Test
    public void channelQuantizationRoundTripsForEveryByte() {
        for (int value = 0; value <= 255; value++) {
            int rgb = value << 16;
            float channel = ChainPreviewShaderMath.colorChannel(rgb, 16);
            Assert.assertEquals(
                "通道 " + value + " 必须按 /255 量化",
                value / 255.0F,
                channel,
                0.0F);
            Assert.assertEquals(
                "通道 " + value + " 必须可逆",
                value,
                ChainPreviewShaderMath.quantizeChannel(channel));
        }
        Assert.assertEquals(255, ChainPreviewShaderMath.quantizeChannel(9.0F));
        Assert.assertEquals(0, ChainPreviewShaderMath.quantizeChannel(-1.0F));
        Assert.assertEquals(
            "NaN 经 clamp 保持 NaN，(int) 收敛为 0",
            0,
            ChainPreviewShaderMath.quantizeChannel(Float.NaN));
    }

    @Test
    public void builtinDiffersFromQuantizedBaselineOnlyWithinTwoThousandths() {
        float[] builtin = ChainPreviewShaderMath.builtinColorRgb(0);
        float[] quantized = ChainPreviewShaderMath.semanticColorRgb(0, 0x40E6FF, 0x40E6FF, 0x40E6FF, 0x40E6FF);
        for (int channel = 0; channel < 3; channel++) {
            float diff = Math.abs(builtin[channel] - quantized[channel]);
            Assert.assertTrue(
                "通道 " + channel + " 差异必须在 0.002 内（builtin 用精确常量规避 8bit 量化）：" + diff,
                diff <= 0.002F);
        }
        Assert.assertTrue(
            "G 通道必须体现量化差异（0.9 vs 230/255），否则本断言会平凡通过",
            Math.abs(builtin[1] - quantized[1]) > 0.001F);
    }

    @Test
    public void colorsFaceNormalizesSourceAndMasksChannels() {
        Colors config = Colors.fromConfig("config", 0x123456, -1, 0x1000000, 0xABCDEF);
        Assert.assertEquals(Colors.SOURCE_CONFIG, config.getSourceId());
        Assert.assertEquals(0x123456, config.getPrimary());
        Assert.assertEquals(0xFFFFFF, config.getSecondary());
        Assert.assertEquals(0x000000, config.getRemote());
        Assert.assertEquals(0xABCDEF, config.getTruncated());

        Assert.assertSame(
            "builtin 来源必须直接复用基线常量",
            Colors.BUILTIN,
            Colors.fromConfig("builtin", 0x123456, 0x123456, 0x123456, 0x123456));
        Assert.assertSame(Colors.BUILTIN, Colors.fromConfig(null, 1, 2, 3, 4));
        Assert.assertSame(Colors.BUILTIN, Colors.fromConfig("unknown", 1, 2, 3, 4));
        Assert.assertEquals(Colors.SOURCE_BUILTIN, Colors.BUILTIN.getSourceId());
        Assert.assertEquals(0x40E6FF, Colors.BUILTIN.getPrimary());
    }

    @Test
    public void planCarriesColorsFaceAndDefaultsToBuiltin() {
        ChainPreviewMeshBuilder builder = new ChainPreviewMeshBuilder();
        ChainPreviewMesh mesh = builder.build(
            VerifyShapes.line(2),
            new VisualParameters(0.5D, 0.5D, 0.5D, 2.0D, 6.0D, 0.8F, 0.2F, 0.045F));

        ChainPreviewDrawPlan defaultPlan = ChainPreviewDrawPlan.derive(
            mesh, 0, mesh.getIndexCount(), null, null,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(Colors.SOURCE_BUILTIN, defaultPlan.getColorSourceId());
        Assert.assertEquals(0x40E6FF, defaultPlan.getColorPrimary());
        Assert.assertEquals(0x40E6FF, defaultPlan.getColorSecondary());
        Assert.assertEquals(0x40E6FF, defaultPlan.getColorRemote());
        Assert.assertEquals(0x40E6FF, defaultPlan.getColorTruncated());

        Colors config = Colors.fromConfig("config", PRIMARY, SECONDARY, REMOTE, TRUNCATED);
        ChainPreviewDrawPlan.Visuals visuals = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F, DepthChannel.XRAY, 1.0F, config);
        ChainPreviewDrawPlan configured = ChainPreviewDrawPlan.derive(
            mesh, 0, mesh.getIndexCount(), null, visuals,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(Colors.SOURCE_CONFIG, configured.getColorSourceId());
        Assert.assertEquals(PRIMARY, configured.getColorPrimary());
        Assert.assertEquals(SECONDARY, configured.getColorSecondary());
        Assert.assertEquals(REMOTE, configured.getColorRemote());
        Assert.assertEquals(TRUNCATED, configured.getColorTruncated());
        Assert.assertSame(
            "值未变化时必须返回同一颜色面实例（零分配）",
            config,
            configured.getVisuals().getColors());
    }

    private static void assertRgb(float[] rgb, int rgbInt) {
        assertRgb("rgb=" + Integer.toHexString(rgbInt), rgb, rgbInt);
    }

    private static void assertRgb(String label, float[] rgb, int rgbInt) {
        Assert.assertEquals(label + " R", ((rgbInt >>> 16) & 0xFF) / 255.0F, rgb[0], 0.0F);
        Assert.assertEquals(label + " G", ((rgbInt >>> 8) & 0xFF) / 255.0F, rgb[1], 0.0F);
        Assert.assertEquals(label + " B", (rgbInt & 0xFF) / 255.0F, rgb[2], 0.0F);
    }
}
