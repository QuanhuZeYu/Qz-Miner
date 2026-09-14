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
 * T17 颜色语义与来源解耦独立探针（T16c + 语义类别表（真源：ChainPreviewSemanticClass）；本轮按大模式扩到六槽）。
 *
 * <p>独立口径：builtin 档 CHAIN 槽对任意「CHAIN 或未定义」类别都必须返回精确基线常量
 * (0.25, 0.9, 1.0)（逐位），其余类别返回各自槽位；config 档按类别选六色并按 8bit 量化（/255）；
 * 类别 6/7/255/未知一律兜底 CHAIN 色；颜色面 Colors 归一化来源 id 并按 0xFFFFFF 收窄通道；
 * plan 透传颜色面。</p>
 */
public class SemanticColorContractTest {

    private static final int CHAIN = 0x112233;
    private static final int AREA = 0x223344;
    private static final int INTERACT = 0x334455;
    private static final int SECONDARY = 0x445566;
    private static final int REMOTE = 0x778899;
    private static final int TRUNCATED = 0xAABBCC;

    @Test
    public void builtinChainSlotIsExactBaselineAndUnknownClassesFallBackToIt() {
        float[] chain = ChainPreviewShaderMath.builtinColorRgb(0);
        Assert.assertEquals("CHAIN 槽 R", Float.floatToIntBits(0.25F), Float.floatToIntBits(chain[0]));
        Assert.assertEquals("CHAIN 槽 G", Float.floatToIntBits(0.9F), Float.floatToIntBits(chain[1]));
        Assert.assertEquals("CHAIN 槽 B", Float.floatToIntBits(1.0F), Float.floatToIntBits(chain[2]));

        int[] fallbackClasses = {6, 7, 8, 99, 254, 255, -1, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int semanticClass : fallbackClasses) {
            Assert.assertSame(
                "class=" + semanticClass + " 必须兜底 CHAIN 槽（同一个静态数组元素）",
                chain,
                ChainPreviewShaderMath.builtinColorRgb(semanticClass));
        }
    }

    /**
     * builtin 档每个已定义类别都拿到自己的槽位。
     *
     * <p>槽 0（CHAIN）是唯一例外：它取精确基线常量 (0.25, 0.9, 1.0)，而不是 0x40E6FF 的 /255 量化值
     * （见 {@code ChainPreviewShaderMath.BUILTIN_COLOR_TABLE} 的说明）。</p>
     */
    @Test
    public void builtinPaletteServesEveryDefinedClassWithItsOwnSlot() {
        int[][] quantizedSlots = {
            {1, ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_AREA_RGB},
            {2, ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_INTERACT_RGB},
            {3, ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_SUB_MODE_RGB},
            {4, ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_REMOTE_RGB},
            {5, ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_TRUNCATED_RGB},
        };
        for (int[] row : quantizedSlots) {
            float[] rgb = ChainPreviewShaderMath.builtinColorRgb(row[0]);
            Assert.assertEquals("class=" + row[0] + " 的 R",
                ChainPreviewShaderMath.colorChannel(row[1], 16), rgb[0], 0.0F);
            Assert.assertEquals("class=" + row[0] + " 的 G",
                ChainPreviewShaderMath.colorChannel(row[1], 8), rgb[1], 0.0F);
            Assert.assertEquals("class=" + row[0] + " 的 B",
                ChainPreviewShaderMath.colorChannel(row[1], 0), rgb[2], 0.0F);
        }

        float[] chain = ChainPreviewShaderMath.builtinColorRgb(0);
        Assert.assertEquals("class=0 的 R 必须是精确常量", 0.25F, chain[0], 0.0F);
        Assert.assertEquals("class=0 的 G 必须是精确常量", 0.9F, chain[1], 0.0F);
        Assert.assertEquals("class=0 的 B 必须是精确常量", 1.0F, chain[2], 0.0F);
    }

    @Test
    public void configPaletteMapsClassToSixColorsExactly() {
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(0, CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED),
            CHAIN);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(1, CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED),
            AREA);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(2, CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED),
            INTERACT);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(3, CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED),
            SECONDARY);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(4, CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED),
            REMOTE);
        assertRgb(ChainPreviewShaderMath.semanticColorRgb(5, CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED),
            TRUNCATED);
        int[] fallbackClasses = {6, 7, 8, 99, 254, 255, -1, Integer.MAX_VALUE};
        for (int semanticClass : fallbackClasses) {
            assertRgb(
                "class=" + semanticClass + " 必须兜底 CHAIN 色",
                ChainPreviewShaderMath.semanticColorRgb(
                    semanticClass, CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED),
                CHAIN);
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
    public void builtinChainDiffersFromQuantizedBaselineOnlyWithinTwoThousandths() {
        float[] builtin = ChainPreviewShaderMath.builtinColorRgb(0);
        float[] quantized = ChainPreviewShaderMath.semanticColorRgb(
            0, 0x40E6FF, 0x40E6FF, 0x40E6FF, 0x40E6FF, 0x40E6FF, 0x40E6FF);
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
        Colors config = Colors.fromConfig("config", 0x123456, -1, 0x1000000, 0x234567, 0x345678, 0xABCDEF);
        Assert.assertEquals(Colors.SOURCE_CONFIG, config.getSourceId());
        Assert.assertEquals(0x123456, config.getChain());
        Assert.assertEquals(0xFFFFFF, config.getArea());
        Assert.assertEquals(0x000000, config.getInteract());
        Assert.assertEquals(0x234567, config.getSecondary());
        Assert.assertEquals(0x345678, config.getRemote());
        Assert.assertEquals(0xABCDEF, config.getTruncated());

        Assert.assertSame(
            "builtin 来源必须直接复用基线常量",
            Colors.BUILTIN,
            Colors.fromConfig("builtin", 0x123456, 0x123456, 0x123456, 0x123456, 0x123456, 0x123456));
        Assert.assertSame(Colors.BUILTIN, Colors.fromConfig(null, 1, 2, 3, 4, 5, 6));
        Assert.assertSame(Colors.BUILTIN, Colors.fromConfig("unknown", 1, 2, 3, 4, 5, 6));
        Assert.assertEquals(Colors.SOURCE_BUILTIN, Colors.BUILTIN.getSourceId());
        Assert.assertEquals(0x40E6FF, Colors.BUILTIN.getChain());
    }

    @Test
    public void builtinColorsFaceHasSixDistinctSlots() {
        int[] slots = {
            Colors.BUILTIN.getChain(), Colors.BUILTIN.getArea(), Colors.BUILTIN.getInteract(),
            Colors.BUILTIN.getSecondary(), Colors.BUILTIN.getRemote(), Colors.BUILTIN.getTruncated()};
        Assert.assertEquals("色槽数必须与调色板槽位数同源",
            ChainPreviewShaderMath.PALETTE_SLOT_COUNT, slots.length);
        for (int a = 0; a < slots.length; a++) {
            for (int b = a + 1; b < slots.length; b++) {
                Assert.assertNotEquals(
                    "builtin 槽位 " + a + " 与 " + b + " 不得同色（默认档必须按大模式可区分）",
                    Integer.valueOf(slots[a]), Integer.valueOf(slots[b]));
            }
        }
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
        Assert.assertEquals(0x40E6FF, defaultPlan.getColorChain());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_AREA_RGB, defaultPlan.getColorArea());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_INTERACT_RGB,
            defaultPlan.getColorInteract());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_SUB_MODE_RGB,
            defaultPlan.getColorSecondary());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_REMOTE_RGB, defaultPlan.getColorRemote());
        Assert.assertEquals(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_TRUNCATED_RGB,
            defaultPlan.getColorTruncated());

        Colors config = Colors.fromConfig("config", CHAIN, AREA, INTERACT, SECONDARY, REMOTE, TRUNCATED);
        ChainPreviewDrawPlan.Visuals visuals = new ChainPreviewDrawPlan.Visuals(
            0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F, DepthChannel.XRAY, 1.0F, config);
        ChainPreviewDrawPlan configured = ChainPreviewDrawPlan.derive(
            mesh, 0, mesh.getIndexCount(), null, visuals,
            ChainPreviewDrawPlan.SEMANTIC_MASK_ALL, 0, 0, 0, 0L, 0L);
        Assert.assertEquals(Colors.SOURCE_CONFIG, configured.getColorSourceId());
        Assert.assertEquals(CHAIN, configured.getColorChain());
        Assert.assertEquals(AREA, configured.getColorArea());
        Assert.assertEquals(INTERACT, configured.getColorInteract());
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
