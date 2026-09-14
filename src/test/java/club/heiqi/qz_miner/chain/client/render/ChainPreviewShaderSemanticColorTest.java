package club.heiqi.qz_miner.chain.client.render;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;

/**
 * 语义类别取色的行为契约（task-16c，语义类别表（真源：ChainPreviewSemanticClass）；本轮按大模式重排值域与调色板槽位）。
 *
 * <p>取色链路共四段，逐段可证伪：</p>
 * <ol>
 *   <li><b>aAux.x → 类别 id</b>：归一化 ubyte 精确还原（round(x×255)）；</li>
 *   <li><b>类别 id → 调色板槽位</b>：0/1/2→CHAIN/AREA/INTERACT、3→扩展子模式、4→远端、5→截断，
 *       6/7/255→CHAIN 兜底；</li>
 *   <li><b>槽位 → 最终 RGB</b>：builtin 档 CHAIN 槽为精确基线常量 (0.25,0.9,1.0)（默认档逐字节等于
 *       现状），其余五槽是显式 0xRRGGBB 的 /255 换算；config 档按 8bit 量化（差异只在该档）；
 *       后端把 plan 映射成六槽 uniform 取值的那一步是纯函数
 *       （{@link ChainPreviewShaderBackend#paletteUniforms(ChainPreviewDrawPlan, float[][])}），
 *       同样按数值断言；</li>
 *   <li><b>插值语义（F1）</b>：选色必须在<strong>顶点阶段</strong>完成。varying 是 smooth 插值的，
 *       同一 quad 内两顶点类别不同时插值落在两个整数之间——若片元再用 {@code ==} 比较，插值结果可能
 *       恰好命中<em>另一个</em>类别的整数而画出错误颜色（本文件用一个具体数值证明这一点）。
 *       GLSL 侧不做源码文本匹配（重命名即误报、改系数却照样绿），改为真机验证 + shader 头部
 *       「实机验证记录」标记（注释改动不触发重验）。</li>
 * </ol>
 */
public class ChainPreviewShaderSemanticColorTest {

    /** legacy 基线常量（builtin 档 CHAIN 槽）。 */
    private static final float BASE_R = 0.25F;
    private static final float BASE_G = 0.9F;
    private static final float BASE_B = 1.0F;

    /** config 档测试用六色（彼此可区分，便于断言逐槽对应）。 */
    private static final int CONFIG_CHAIN = 0x40E6FF;
    private static final int CONFIG_AREA = 0x00FF00;
    private static final int CONFIG_INTERACT = 0xFFFF00;
    private static final int CONFIG_SECONDARY = 0x00FFFF;
    private static final int CONFIG_REMOTE = 0xFF8000;
    private static final int CONFIG_TRUNCATED = 0xFF0000;

    // ------------------------------------------------------------------ 段 1：aAux.x → 类别 id

    @Test
    public void semanticClassRoundTripsFromNormalizedByte() {
        for (int semantic = 0; semantic <= 255; semantic++) {
            Assert.assertEquals("类别 " + semantic + " 必须精确还原",
                    semantic, ChainPreviewShaderMath.unquantizeChannel(semantic / 255.0F));
        }
    }

    // ------------------------------------------------------------------ 段 2：类别 → 槽位

    /** 语义类别表（真源：ChainPreviewSemanticClass）逐条映射（0/1/2→三大模式、3→扩展子模式、4→远端、5→截断，6/7/255→CHAIN 兜底）。 */
    @Test
    public void paletteIndexFollowsFrozenCategoryTable() {
        Assert.assertEquals("0 CHAIN_LOCAL → CHAIN 色", ChainPreviewShaderMath.PALETTE_CHAIN,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.CHAIN_LOCAL));
        Assert.assertEquals("1 AREA_LOCAL → AREA 色", ChainPreviewShaderMath.PALETTE_AREA,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.AREA_LOCAL));
        Assert.assertEquals("2 INTERACT_LOCAL → INTERACT 色", ChainPreviewShaderMath.PALETTE_INTERACT,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.INTERACT_LOCAL));
        Assert.assertEquals("3 SUB_MODE_LOCAL → 扩展子模式色", ChainPreviewShaderMath.PALETTE_SECONDARY,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.SUB_MODE_LOCAL));
        Assert.assertEquals("4 REMOTE_PREDICTED → 远端色", ChainPreviewShaderMath.PALETTE_REMOTE,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.REMOTE_PREDICTED));
        Assert.assertEquals("5 TRUNCATED → 截断色", ChainPreviewShaderMath.PALETTE_TRUNCATED,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.TRUNCATED));
        Assert.assertEquals("6 DEFERRED → CHAIN 兜底", ChainPreviewShaderMath.PALETTE_CHAIN,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.DEFERRED));
        Assert.assertEquals("7 EXECUTED → CHAIN 兜底", ChainPreviewShaderMath.PALETTE_CHAIN,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.EXECUTED));
        Assert.assertEquals("255 UNDEFINED → CHAIN 兜底", ChainPreviewShaderMath.PALETTE_CHAIN,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.UNDEFINED));

        // 全值域扫描：任何输入都必须落在合法槽位（不得出现空洞/异常色）
        for (int semantic = 0; semantic <= 255; semantic++) {
            int slot = ChainPreviewShaderMath.paletteIndexFor(semantic);
            Assert.assertTrue("类别 " + semantic + " 必须映射到合法槽位，实际=" + slot,
                    slot >= ChainPreviewShaderMath.PALETTE_CHAIN
                            && slot <= ChainPreviewShaderMath.PALETTE_TRUNCATED);
        }
    }

    /** F3：类别常量必须只有单一真源（Java 侧引用 session-core 的常量类）。 */
    @Test
    public void semanticConstantsShareSingleSourceOfTruth() {
        Assert.assertEquals(ChainPreviewSemanticClass.CHAIN_LOCAL,
                ChainPreviewShaderMath.SEMANTIC_CHAIN_LOCAL);
        Assert.assertEquals(ChainPreviewSemanticClass.AREA_LOCAL,
                ChainPreviewShaderMath.SEMANTIC_AREA_LOCAL);
        Assert.assertEquals(ChainPreviewSemanticClass.INTERACT_LOCAL,
                ChainPreviewShaderMath.SEMANTIC_INTERACT_LOCAL);
        Assert.assertEquals(ChainPreviewSemanticClass.SUB_MODE_LOCAL,
                ChainPreviewShaderMath.SEMANTIC_SUB_MODE_LOCAL);
        Assert.assertEquals(ChainPreviewSemanticClass.REMOTE_PREDICTED,
                ChainPreviewShaderMath.SEMANTIC_REMOTE_PREDICTED);
        Assert.assertEquals(ChainPreviewSemanticClass.TRUNCATED,
                ChainPreviewShaderMath.SEMANTIC_TRUNCATED);
        Assert.assertEquals(ChainPreviewSemanticClass.DEFERRED,
                ChainPreviewShaderMath.SEMANTIC_DEFERRED);
        Assert.assertEquals(ChainPreviewSemanticClass.EXECUTED,
                ChainPreviewShaderMath.SEMANTIC_EXECUTED);
        Assert.assertEquals(ChainPreviewSemanticClass.UNDEFINED,
                ChainPreviewShaderMath.SEMANTIC_UNDEFINED);
    }

    // ------------------------------------------------------------------ 段 3：槽位 → RGB

    /**
     * builtin 档：六个槽位、CHAIN 槽逐位等于 legacy 常量、其余五槽按 /255 取显式色，
     * 且六槽两两不同（本任务「默认就要可区分」的核心断言）。
     */
    @Test
    public void builtinPaletteHasSixDistinctSlotsWithExactChainBaseline() {
        float[][] table = ChainPreviewShaderMath.builtinColorTable();
        Assert.assertEquals("builtin 调色板必须有 6 个槽位",
                ChainPreviewShaderMath.PALETTE_SLOT_COUNT, table.length);

        Assert.assertEquals("CHAIN 槽 R 必须逐位等于 0.25", BASE_R,
                table[ChainPreviewShaderMath.PALETTE_CHAIN][0], 0.0F);
        Assert.assertEquals("CHAIN 槽 G 必须逐位等于 0.9", BASE_G,
                table[ChainPreviewShaderMath.PALETTE_CHAIN][1], 0.0F);
        Assert.assertEquals("CHAIN 槽 B 必须逐位等于 1.0", BASE_B,
                table[ChainPreviewShaderMath.PALETTE_CHAIN][2], 0.0F);

        int[] builtinRgb = ChainPreviewShaderMath.builtinPalette();
        for (int slot = ChainPreviewShaderMath.PALETTE_AREA;
                slot <= ChainPreviewShaderMath.PALETTE_TRUNCATED; slot++) {
            Assert.assertEquals("槽位 " + slot + " 的 R", ChainPreviewShaderMath.colorChannel(builtinRgb[slot], 16),
                    table[slot][0], 0.0F);
            Assert.assertEquals("槽位 " + slot + " 的 G", ChainPreviewShaderMath.colorChannel(builtinRgb[slot], 8),
                    table[slot][1], 0.0F);
            Assert.assertEquals("槽位 " + slot + " 的 B", ChainPreviewShaderMath.colorChannel(builtinRgb[slot], 0),
                    table[slot][2], 0.0F);
        }

        for (int a = 0; a < builtinRgb.length; a++) {
            for (int b = a + 1; b < builtinRgb.length; b++) {
                Assert.assertNotEquals(
                        "builtin 槽位 " + a + " 与 " + b + " 不得同色（默认档必须按大模式可区分）",
                        Integer.valueOf(builtinRgb[a]), Integer.valueOf(builtinRgb[b]));
            }
        }
    }

    /** F4：builtin 调色板必须静态复用（每帧 uniform 路径不得产生分配）。 */
    @Test
    public void builtinPaletteIsReusedNotAllocated() {
        Assert.assertSame("多次取用必须返回同一实例（避免每帧分配）",
                ChainPreviewShaderMath.builtinColorTable(), ChainPreviewShaderMath.builtinColorTable());
        Assert.assertSame("builtinColorRgb 必须返回静态数组元素，不得 clone",
                ChainPreviewShaderMath.builtinColorTable()[ChainPreviewShaderMath.PALETTE_CHAIN],
                ChainPreviewShaderMath.builtinColorRgb(ChainPreviewSemanticClass.CHAIN_LOCAL));
    }

    /** config 档：每个类别 id → 期望 RGB（含兜底与未知值）。 */
    @Test
    public void configPaletteSelectsExpectedColorPerCategory() {
        assertRgb("0 CHAIN_LOCAL", ChainPreviewSemanticClass.CHAIN_LOCAL, CONFIG_CHAIN);
        assertRgb("1 AREA_LOCAL", ChainPreviewSemanticClass.AREA_LOCAL, CONFIG_AREA);
        assertRgb("2 INTERACT_LOCAL", ChainPreviewSemanticClass.INTERACT_LOCAL, CONFIG_INTERACT);
        assertRgb("3 SUB_MODE_LOCAL", ChainPreviewSemanticClass.SUB_MODE_LOCAL, CONFIG_SECONDARY);
        assertRgb("4 REMOTE_PREDICTED", ChainPreviewSemanticClass.REMOTE_PREDICTED, CONFIG_REMOTE);
        assertRgb("5 TRUNCATED", ChainPreviewSemanticClass.TRUNCATED, CONFIG_TRUNCATED);
        assertRgb("6 DEFERRED（未启用）", ChainPreviewSemanticClass.DEFERRED, CONFIG_CHAIN);
        assertRgb("7 EXECUTED（未启用）", ChainPreviewSemanticClass.EXECUTED, CONFIG_CHAIN);
        assertRgb("255 UNDEFINED", ChainPreviewSemanticClass.UNDEFINED, CONFIG_CHAIN);
        assertRgb("99 未知值", 99, CONFIG_CHAIN);
    }

    /** 未知颜色来源 id 必须回落 builtin，不得产生异常色。 */
    @Test
    public void unknownColorSourceFallsBackToBuiltinPalette() {
        Assert.assertArrayEquals("null 来源必须回落 builtin", ChainPreviewShaderMath.builtinPalette(),
                ChainPreviewShaderMath.paletteFor(null, CONFIG_CHAIN, CONFIG_AREA, CONFIG_INTERACT,
                        CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED));
        Assert.assertArrayEquals("未知来源必须回落 builtin", ChainPreviewShaderMath.builtinPalette(),
                ChainPreviewShaderMath.paletteFor("rainbow", CONFIG_CHAIN, CONFIG_AREA, CONFIG_INTERACT,
                        CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED));
        Assert.assertArrayEquals("config 来源必须使用配置色",
                new int[] {CONFIG_CHAIN, CONFIG_AREA, CONFIG_INTERACT,
                        CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED},
                ChainPreviewShaderMath.paletteFor(ChainPreviewShaderMath.COLOR_SOURCE_CONFIG,
                        CONFIG_CHAIN, CONFIG_AREA, CONFIG_INTERACT,
                        CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED));
    }

    /** config 档量化：int RGB → float 必须按 /255，且量化差异只出现在该档。 */
    @Test
    public void configColorIsQuantizedPerByte() {
        Assert.assertEquals("0x40 → 64/255", 64 / 255.0F,
                ChainPreviewShaderMath.colorChannel(CONFIG_CHAIN, 16), 0.0F);
        Assert.assertEquals("0xE6 → 230/255", 230 / 255.0F,
                ChainPreviewShaderMath.colorChannel(CONFIG_CHAIN, 8), 0.0F);
        Assert.assertEquals("0xFF → 1.0", 1.0F, ChainPreviewShaderMath.colorChannel(CONFIG_CHAIN, 0), 0.0F);
        for (int value = 0; value <= 255; value++) {
            int rgb = (value << 16) | (value << 8) | value;
            Assert.assertEquals("通道 " + value + " 必须量化闭合",
                    value, ChainPreviewShaderMath.quantizeChannel(ChainPreviewShaderMath.colorChannel(rgb, 16)));
        }
        Assert.assertNotEquals("config 量化值与 builtin 基线常量不同（两档口径可区分）",
                BASE_R, ChainPreviewShaderMath.colorChannel(CONFIG_CHAIN, 16), 0.0F);
    }

    /**
     * config 档接线：后端必须按 plan 的六色设 uniform，且 builtin 档必须传静态精确表。
     *
     * <p>断言打在 {@link ChainPreviewShaderBackend#paletteUniforms(ChainPreviewDrawPlan, float[][])}
     * 的<b>六槽数值</b>上（「给定 plan 产出什么颜色」才是接线契约）：config 档必须真的取 plan 六色
     * 并按 8bit 量化（否则配置色永远不生效）；builtin 档必须是静态表本身，且 CHAIN 槽是精确基线
     * 常量而不是 0x40E6FF 的量化值（否则引入 1.96e-3 色差，破坏「默认档逐字节等于现状」）。</p>
     */
    @Test
    public void paletteUniformsFollowPlanColorSourceWithoutQuantizingBuiltin() {
        float[][] configScratch =
                new float[ChainPreviewShaderMath.PALETTE_SLOT_COUNT][3];
        float[][] configured = ChainPreviewShaderBackend.paletteUniforms(configPlan(
                CONFIG_CHAIN, CONFIG_AREA, CONFIG_INTERACT,
                CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED), configScratch);
        Assert.assertSame("config 档必须写进调用方缓冲（每帧零分配）", configScratch, configured);
        assertPaletteSlot("config CHAIN 色", configured, ChainPreviewShaderMath.PALETTE_CHAIN, CONFIG_CHAIN);
        assertPaletteSlot("config AREA 色", configured, ChainPreviewShaderMath.PALETTE_AREA, CONFIG_AREA);
        assertPaletteSlot("config INTERACT 色", configured, ChainPreviewShaderMath.PALETTE_INTERACT,
                CONFIG_INTERACT);
        assertPaletteSlot("config 扩展子模式色", configured, ChainPreviewShaderMath.PALETTE_SECONDARY,
                CONFIG_SECONDARY);
        assertPaletteSlot("config 远端色", configured, ChainPreviewShaderMath.PALETTE_REMOTE, CONFIG_REMOTE);
        assertPaletteSlot("config 截断色", configured, ChainPreviewShaderMath.PALETTE_TRUNCATED, CONFIG_TRUNCATED);

        float[][] builtin = ChainPreviewShaderBackend.paletteUniforms(builtinPlan(), configScratch);
        Assert.assertSame("builtin 档必须复用静态精确表（每帧零分配）",
                ChainPreviewShaderMath.builtinColorTable(), builtin);
        Assert.assertEquals("builtin CHAIN 槽 R 必须逐位等于 0.25", BASE_R,
                builtin[ChainPreviewShaderMath.PALETTE_CHAIN][0], 0.0F);
        Assert.assertEquals("builtin CHAIN 槽 G 必须逐位等于 0.9", BASE_G,
                builtin[ChainPreviewShaderMath.PALETTE_CHAIN][1], 0.0F);
        Assert.assertEquals("builtin CHAIN 槽 B 必须逐位等于 1.0", BASE_B,
                builtin[ChainPreviewShaderMath.PALETTE_CHAIN][2], 0.0F);
        Assert.assertNotEquals("builtin CHAIN 档的 G 不得是量化值 230/255",
                ChainPreviewShaderMath.colorChannel(
                        ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_CHAIN_RGB, 8),
                builtin[ChainPreviewShaderMath.PALETTE_CHAIN][1]);
    }

    /** builtin 来源 plan（六槽取 Colors.BUILTIN 的量化口径）与 config 色源 plan 的构造。 */
    private static ChainPreviewDrawPlan builtinPlan() {
        return planWithColors(ChainPreviewDrawPlan.Visuals.Colors.BUILTIN);
    }

    private static ChainPreviewDrawPlan configPlan(
            int chain, int area, int interact, int secondary, int remote, int truncated) {
        return planWithColors(ChainPreviewDrawPlan.Visuals.Colors.fromConfig(
                ChainPreviewShaderMath.COLOR_SOURCE_CONFIG,
                chain, area, interact, secondary, remote, truncated));
    }

    private static ChainPreviewDrawPlan planWithColors(ChainPreviewDrawPlan.Visuals.Colors colors) {
        return new ChainPreviewDrawPlan(
                0, 4, null,
                new ChainPreviewDrawPlan.Visuals(
                        0.045F, 0.0F, 1.0F, 2.0F, 6.0F, 0.78F, 0.15F,
                        ChainPreviewDrawPlan.DepthChannel.XRAY, 1.0F, colors),
                ChainPreviewDrawPlan.SEMANTIC_MASK_ALL,
                0, 0, 0, 4, false, 0, 0L, 0L);
    }

    /** 逐通道断言某槽位等于 int RGB 的 8bit 量化结果（delta=0：量化必须逐位一致）。 */
    private static void assertPaletteSlot(String label, float[][] palette, int slot, int expectedRgb) {
        Assert.assertEquals(label + " 的 R", ChainPreviewShaderMath.colorChannel(expectedRgb, 16),
                palette[slot][0], 0.0F);
        Assert.assertEquals(label + " 的 G", ChainPreviewShaderMath.colorChannel(expectedRgb, 8),
                palette[slot][1], 0.0F);
        Assert.assertEquals(label + " 的 B", ChainPreviewShaderMath.colorChannel(expectedRgb, 0),
                palette[slot][2], 0.0F);
    }

    /** plan 的 builtin 量化值与精确常量的差必须被显式认知（防止有人「顺手」改用它）。 */
    @Test
    public void quantizedBaselineDiffersFromExactConstant() {
        int quantized = ChainPreviewDrawPlan.Visuals.Colors.BUILTIN_CHAIN_RGB;
        Assert.assertEquals("plan BUILTIN_CHAIN_RGB 必须仍是 0x40E6FF", 0x40E6FF, quantized);
        Assert.assertEquals("其 G 通道是 230/255", 230 / 255.0F,
                ChainPreviewShaderMath.colorChannel(quantized, 8), 0.0F);
        Assert.assertNotEquals("它与精确常量 0.9F 相差约 1.96e-3 —— 所以 builtin 档不能用它",
                BASE_G, ChainPreviewShaderMath.colorChannel(quantized, 8), 1.0e-4F);
        Assert.assertEquals("精确常量必须是 0.9F", BASE_G, ChainPreviewShaderMath.BUILTIN_COLOR_GREEN, 0.0F);
        Assert.assertEquals("0x40E6FF 的 R 也不是 0.25",
                64 / 255.0F, ChainPreviewShaderMath.colorChannel(quantized, 16), 0.0F);
    }

    // ------------------------------------------------------------------ 段 4：F1 插值语义

    /**
     * 插值模型：两顶点类别不同 → 顶点阶段已定色的结果参与混合，不会丢色。
     *
     * <p>反证（为什么不能在片元用 {@code ==} 选类别）：CHAIN_LOCAL=0 与 REMOTE_PREDICTED=4
     * 的插值中点是 <b>2.0</b>，恰好等于 INTERACT_LOCAL —— 片元比较会画出一种「合法但错误」的颜色。
     * 本断言把这个数值事实固化为契约。</p>
     */
    @Test
    public void interpolatedFragmentKeepsBothCategoryColors() {
        float[] remote = rgbOf(ChainPreviewSemanticClass.REMOTE_PREDICTED);
        float[] chain = rgbOf(ChainPreviewSemanticClass.CHAIN_LOCAL);

        // 模拟 quad 内插值参数 t=0.5（两顶点分别为类别 0 与类别 4）
        float t = 0.5F;
        float[] interpolated = {
            chain[0] * (1.0F - t) + remote[0] * t,
            chain[1] * (1.0F - t) + remote[1] * t,
            chain[2] * (1.0F - t) + remote[2] * t,
        };
        Assert.assertEquals("混合色必须落在两类别色之间（R）",
                (chain[0] + remote[0]) * 0.5F, interpolated[0], 1.0e-6F);
        Assert.assertNotEquals("混合色不得等于 CHAIN 色（旧实现在此会整片落回兜底色）",
                chain[0], interpolated[0], 1.0e-6F);
        Assert.assertNotEquals("混合色不得等于远端色（说明发生了真实插值）",
                remote[0], interpolated[0], 1.0e-6F);

        float interpolatedSemantic = ChainPreviewSemanticClass.CHAIN_LOCAL * (1.0F - t)
                + ChainPreviewSemanticClass.REMOTE_PREDICTED * t;
        Assert.assertNotEquals("插值后的类别值不再是任一端点",
                (float) ChainPreviewSemanticClass.REMOTE_PREDICTED, interpolatedSemantic, 0.0F);
        Assert.assertEquals("插值中点恰好等于另一个合法类别 id（片元 == 比较会误命中 INTERACT 色）",
                (float) ChainPreviewSemanticClass.INTERACT_LOCAL, interpolatedSemantic, 0.0F);
        Assert.assertEquals("该误命中的类别在调色板里是独立槽位（不是兜底色）",
                ChainPreviewShaderMath.PALETTE_INTERACT,
                ChainPreviewShaderMath.paletteIndexFor((int) interpolatedSemantic));
    }

    // ------------------------------------------------------------------ 辅助

    private static float[] rgbOf(int semanticClass) {
        return ChainPreviewShaderMath.semanticColorRgb(semanticClass,
                CONFIG_CHAIN, CONFIG_AREA, CONFIG_INTERACT,
                CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED);
    }

    private static void assertRgb(String label, int semanticClass, int expectedRgb) {
        float[] rgb = rgbOf(semanticClass);
        Assert.assertEquals(label + " 的 R", ChainPreviewShaderMath.colorChannel(expectedRgb, 16), rgb[0], 0.0F);
        Assert.assertEquals(label + " 的 G", ChainPreviewShaderMath.colorChannel(expectedRgb, 8), rgb[1], 0.0F);
        Assert.assertEquals(label + " 的 B", ChainPreviewShaderMath.colorChannel(expectedRgb, 0), rgb[2], 0.0F);
    }
}
