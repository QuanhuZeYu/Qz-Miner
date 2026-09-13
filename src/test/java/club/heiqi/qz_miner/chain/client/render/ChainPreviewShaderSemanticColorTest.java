package club.heiqi.qz_miner.chain.client.render;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.client.ChainPreviewSemanticClass;

/**
 * 语义类别取色的行为契约（task-16c，接口冻结 §D）。
 *
 * <p>取色链路共四段，逐段可证伪：</p>
 * <ol>
 *   <li><b>aAux.x → 类别 id</b>：归一化 ubyte 精确还原（round(x×255)）；</li>
 *   <li><b>类别 id → 调色板槽位</b>：0→主、1→子模式、2→远端、3→截断，4/5/255→主色兜底；</li>
 *   <li><b>槽位 → 最终 RGB</b>：builtin 档四槽同为精确基线常量 (0.25,0.9,1.0) ⇒ 逐字节等于现状；
 *       config 档按 8bit 量化（差异只在该档）；</li>
 *   <li><b>插值语义（F1）</b>：选色必须在<strong>顶点阶段</strong>完成。varying 是 smooth 插值的，
 *       同一 quad 内两顶点类别不同时插值落在两整数之间——若片元再用 {@code == 2.0} 比较就会整片
 *       落空、丢失远端/截断色。本段用「顶点颜色 → 插值 → 片元输出」的模型证明颜色不再丢失。</li>
 * </ol>
 */
public class ChainPreviewShaderSemanticColorTest {

    private static final String VERTEX_PATH = "src/main/resources/assets/qz_miner/shaders/preview.vert";
    private static final String FRAGMENT_PATH = "src/main/resources/assets/qz_miner/shaders/preview.frag";
    private static final String BACKEND_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderBackend.java";

    private static final float BASE_R = 0.25F;
    private static final float BASE_G = 0.9F;
    private static final float BASE_B = 1.0F;

    /** config 档测试用四色（彼此可区分，便于断言分支）。 */
    private static final int CONFIG_PRIMARY = 0x40E6FF;
    private static final int CONFIG_SECONDARY = 0x00FF00;
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

    /** §D 类别表逐条映射（本轮口径：0→主、1→子模式、2→远端、3→截断，4/5/255→主色兜底）。 */
    @Test
    public void paletteIndexFollowsFrozenCategoryTable() {
        Assert.assertEquals("0 PRIMARY_LOCAL → 主色", ChainPreviewShaderMath.PALETTE_PRIMARY,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.PRIMARY_LOCAL));
        Assert.assertEquals("1 SUB_MODE_LOCAL → 子模式色", ChainPreviewShaderMath.PALETTE_SECONDARY,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.SUB_MODE_LOCAL));
        Assert.assertEquals("2 REMOTE_PREDICTED → 远端色", ChainPreviewShaderMath.PALETTE_REMOTE,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.REMOTE_PREDICTED));
        Assert.assertEquals("3 TRUNCATED → 截断色", ChainPreviewShaderMath.PALETTE_TRUNCATED,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.TRUNCATED));
        Assert.assertEquals("4 DEFERRED → 主色兜底", ChainPreviewShaderMath.PALETTE_PRIMARY,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.DEFERRED));
        Assert.assertEquals("5 EXECUTED → 主色兜底", ChainPreviewShaderMath.PALETTE_PRIMARY,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.EXECUTED));
        Assert.assertEquals("255 UNDEFINED → 主色兜底", ChainPreviewShaderMath.PALETTE_PRIMARY,
                ChainPreviewShaderMath.paletteIndexFor(ChainPreviewSemanticClass.UNDEFINED));

        // 全值域扫描：任何输入都必须落在 4 个合法槽位（不得出现空洞/异常色）
        for (int semantic = 0; semantic <= 255; semantic++) {
            int slot = ChainPreviewShaderMath.paletteIndexFor(semantic);
            Assert.assertTrue("类别 " + semantic + " 必须映射到合法槽位，实际=" + slot,
                    slot >= ChainPreviewShaderMath.PALETTE_PRIMARY
                            && slot <= ChainPreviewShaderMath.PALETTE_TRUNCATED);
        }
    }

    /** F3：类别常量必须只有单一真源（Java 侧引用 session-core 的常量类）。 */
    @Test
    public void semanticConstantsShareSingleSourceOfTruth() {
        Assert.assertEquals(ChainPreviewSemanticClass.PRIMARY_LOCAL,
                ChainPreviewShaderMath.SEMANTIC_PRIMARY_LOCAL);
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

    /** builtin 档：任何类别都必须输出精确基线常量 (0.25, 0.9, 1.0)，逐位等于现状。 */
    @Test
    public void builtinPaletteYieldsBaselineConstantForEveryCategory() {
        float[][] table = ChainPreviewShaderMath.builtinColorTable();
        Assert.assertEquals("builtin 调色板必须有 4 个槽位", 4, table.length);
        for (int slot = 0; slot < table.length; slot++) {
            Assert.assertEquals("槽位 " + slot + " 的 R 必须逐位等于 0.25", BASE_R, table[slot][0], 0.0F);
            Assert.assertEquals("槽位 " + slot + " 的 G 必须逐位等于 0.9", BASE_G, table[slot][1], 0.0F);
            Assert.assertEquals("槽位 " + slot + " 的 B 必须逐位等于 1.0", BASE_B, table[slot][2], 0.0F);
        }
        for (int semantic = 0; semantic <= 255; semantic++) {
            float[] rgb = ChainPreviewShaderMath.builtinColorRgb(semantic);
            Assert.assertEquals("builtin 档类别 " + semantic + " 的 R", BASE_R, rgb[0], 0.0F);
            Assert.assertEquals("builtin 档类别 " + semantic + " 的 G", BASE_G, rgb[1], 0.0F);
            Assert.assertEquals("builtin 档类别 " + semantic + " 的 B", BASE_B, rgb[2], 0.0F);
        }
    }

    /** F4：builtin 调色板必须静态复用（每帧 uniform 路径不得产生分配）。 */
    @Test
    public void builtinPaletteIsReusedNotAllocated() {
        Assert.assertSame("多次取用必须返回同一实例（避免每帧分配）",
                ChainPreviewShaderMath.builtinColorTable(), ChainPreviewShaderMath.builtinColorTable());
        Assert.assertSame("builtinColorRgb 必须返回静态数组元素，不得 clone",
                ChainPreviewShaderMath.builtinColorTable()[ChainPreviewShaderMath.PALETTE_PRIMARY],
                ChainPreviewShaderMath.builtinColorRgb(ChainPreviewSemanticClass.PRIMARY_LOCAL));
    }

    /** config 档：每个类别 id → 期望 RGB（含 1→子模式色与 255 兜底）。 */
    @Test
    public void configPaletteSelectsExpectedColorPerCategory() {
        assertRgb("0 PRIMARY_LOCAL", ChainPreviewSemanticClass.PRIMARY_LOCAL, CONFIG_PRIMARY);
        assertRgb("1 SUB_MODE_LOCAL", ChainPreviewSemanticClass.SUB_MODE_LOCAL, CONFIG_SECONDARY);
        assertRgb("2 REMOTE_PREDICTED", ChainPreviewSemanticClass.REMOTE_PREDICTED, CONFIG_REMOTE);
        assertRgb("3 TRUNCATED", ChainPreviewSemanticClass.TRUNCATED, CONFIG_TRUNCATED);
        assertRgb("4 DEFERRED（未启用）", ChainPreviewSemanticClass.DEFERRED, CONFIG_PRIMARY);
        assertRgb("5 EXECUTED（未启用）", ChainPreviewSemanticClass.EXECUTED, CONFIG_PRIMARY);
        assertRgb("255 UNDEFINED", ChainPreviewSemanticClass.UNDEFINED, CONFIG_PRIMARY);
    }

    /** 未知颜色来源 id 必须回落 builtin，不得产生异常色。 */
    @Test
    public void unknownColorSourceFallsBackToBuiltinPalette() {
        Assert.assertArrayEquals("null 来源必须回落 builtin", ChainPreviewShaderMath.builtinPalette(),
                ChainPreviewShaderMath.paletteFor(null, CONFIG_PRIMARY, CONFIG_SECONDARY,
                        CONFIG_REMOTE, CONFIG_TRUNCATED));
        Assert.assertArrayEquals("未知来源必须回落 builtin", ChainPreviewShaderMath.builtinPalette(),
                ChainPreviewShaderMath.paletteFor("rainbow", CONFIG_PRIMARY, CONFIG_SECONDARY,
                        CONFIG_REMOTE, CONFIG_TRUNCATED));
        Assert.assertArrayEquals("config 来源必须使用配置色",
                new int[] {CONFIG_PRIMARY, CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED},
                ChainPreviewShaderMath.paletteFor(ChainPreviewShaderMath.COLOR_SOURCE_CONFIG,
                        CONFIG_PRIMARY, CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED));
    }

    /** config 档量化：int RGB → float 必须按 /255，且量化差异只出现在该档。 */
    @Test
    public void configColorIsQuantizedPerByte() {
        Assert.assertEquals("0x40 → 64/255", 64 / 255.0F,
                ChainPreviewShaderMath.colorChannel(CONFIG_PRIMARY, 16), 0.0F);
        Assert.assertEquals("0xE6 → 230/255", 230 / 255.0F,
                ChainPreviewShaderMath.colorChannel(CONFIG_PRIMARY, 8), 0.0F);
        Assert.assertEquals("0xFF → 1.0", 1.0F, ChainPreviewShaderMath.colorChannel(CONFIG_PRIMARY, 0), 0.0F);
        for (int value = 0; value <= 255; value++) {
            int rgb = (value << 16) | (value << 8) | value;
            Assert.assertEquals("通道 " + value + " 必须量化闭合",
                    value, ChainPreviewShaderMath.quantizeChannel(ChainPreviewShaderMath.colorChannel(rgb, 16)));
        }
        Assert.assertNotEquals("config 量化值与 builtin 基线常量不同（两档口径可区分）",
                BASE_R, ChainPreviewShaderMath.colorChannel(CONFIG_PRIMARY, 16), 0.0F);
    }

    /**
     * config 档接线：后端必须按 plan 的四色设 uniform，且 builtin 档必须传精确常量。
     *
     * <p>两处关键不能退化：</p>
     * <ol>
     *   <li>builtin 档若误用 plan 的 {@code BUILTIN_RGB}（0x40E6FF 量化值）会引入 1.96e-3 色差，
     *       破坏「逐字节等于现状」；</li>
     *   <li>config 档必须真的读 plan 四色，而不是继续传常量（否则配置色永远不生效）。</li>
     * </ol>
     */
    @Test
    public void backendWiresConfigPaletteFromPlanAndKeepsBuiltinExact() throws Exception {
        String body = stripComments(read(BACKEND_PATH));
        Assert.assertTrue("必须读取 plan 的颜色来源", body.contains("plan.getColorSourceId()"));
        Assert.assertTrue("config 档必须读 plan 四色",
                body.contains("plan.getColorPrimary()") && body.contains("plan.getColorSecondary()")
                        && body.contains("plan.getColorRemote()") && body.contains("plan.getColorTruncated()"));
        Assert.assertTrue("config 档必须按 int RGB 量化设 uniform",
                body.contains("setSemanticColorRgb("));
        Assert.assertTrue("builtin 档必须仍然传精确基线常量（不得走量化值）",
                body.contains("BUILTIN_COLOR_RED") && body.contains("BUILTIN_COLOR_GREEN")
                        && body.contains("BUILTIN_COLOR_BLUE"));
        Assert.assertFalse("builtin 档不得消费 plan 的量化基线值 BUILTIN_RGB",
                body.contains("BUILTIN_RGB"));
    }

    /** plan 的 builtin 量化值与精确常量的差必须被显式认知（防止有人「顺手」改用它）。 */
    @Test
    public void quantizedBaselineDiffersFromExactConstant() {
        int quantized = 0x40E6FF;
        Assert.assertEquals("plan BUILTIN_RGB 的 G 通道是 230/255", 230 / 255.0F,
                ChainPreviewShaderMath.colorChannel(quantized, 8), 0.0F);
        Assert.assertNotEquals("它与精确常量 0.9F 相差约 1.96e-3 —— 所以 builtin 档不能用它",
                BASE_G, ChainPreviewShaderMath.colorChannel(quantized, 8), 1.0e-4F);
        Assert.assertEquals("精确常量必须是 0.9F", BASE_G, ChainPreviewShaderMath.BUILTIN_COLOR_GREEN, 0.0F);
        Assert.assertEquals("0x40E6FF 的 R 也不是 0.25",
                64 / 255.0F, ChainPreviewShaderMath.colorChannel(quantized, 16), 0.0F);
    }

    // ------------------------------------------------------------------ 段 4：F1 插值语义

    /**
     * F1：选色必须在顶点阶段完成，片元只消费插值后的颜色。
     *
     * <p>由于 GLSL 1.20 无 flat 限定符，varying 一律 smooth 插值：一个 quad 内两顶点类别不同
     * （共享角点取相邻目标的最小类别序）时，片元拿到的 vSemantic 会落在两整数之间。
     * 片元若仍用 {@code == 2.0} 比较就会整片落回主色、丢失远端色；把选择搬到顶点即可根治。</p>
     */
    @Test
    public void vertexSelectsColorSoInterpolationCannotLoseCategory() throws Exception {
        String vertex = stripComments(read(VERTEX_PATH));
        Assert.assertTrue("选色函数必须存在，且按类别返回对应槽位色",
                vertex.contains("previewSemanticColor"));
        Assert.assertTrue("类别 1 必须走 uColorSecondary", vertex.contains("return uColorSecondary;"));
        Assert.assertTrue("类别 2 必须走 uColorRemote", vertex.contains("return uColorRemote;"));
        Assert.assertTrue("类别 3 必须走 uColorTruncated", vertex.contains("return uColorTruncated;"));
        Assert.assertTrue("其余类别必须兜底 uColorPrimary", vertex.contains("return uColorPrimary;"));
        Assert.assertTrue("顶点必须把选中的颜色写进 vColor.rgb",
                vertex.contains("vColor = vec4(previewSemanticColor(semanticClass), alpha)"));

        String fragment = stripComments(read(FRAGMENT_PATH));
        Assert.assertTrue("片元必须直接输出插值后的 vColor.rgb",
                fragment.contains("gl_FragColor = vec4(vColor.rgb, vColor.a)"));
        Assert.assertFalse("片元不得再用 vSemantic 做精确比较（这正是丢色的根因）",
                fragment.contains("vSemantic =="));
        Assert.assertFalse("片元不得再声明 uColor*（避免两处真源分叉）",
                fragment.contains("uniform vec3 uColor"));
    }

    /**
     * 插值模型：两顶点类别不同 → 片元颜色是两者的线性混合，不再是「主色兜底」。
     *
     * <p>对照旧实现（片元 {@code ==} 比较）：插值值 2.5 不等于 2.0 也不等于 3.0，
     * 会整片落回主色 —— 远端色占比 0；新实现下远端色参与混合，占比与插值位置一致。</p>
     */
    @Test
    public void interpolatedFragmentKeepsBothCategoryColors() {
        float[] remote = ChainPreviewShaderMath.colorChannel(CONFIG_REMOTE, 16) >= 0.0F
                ? rgbOf(ChainPreviewSemanticClass.REMOTE_PREDICTED)
                : null;
        float[] primary = rgbOf(ChainPreviewSemanticClass.PRIMARY_LOCAL);
        Assert.assertNotNull(remote);

        // 模拟 quad 内插值参数 t=0.5（两顶点分别为类别 0 与类别 2）
        float t = 0.5F;
        float[] interpolated = {
            primary[0] * (1.0F - t) + remote[0] * t,
            primary[1] * (1.0F - t) + remote[1] * t,
            primary[2] * (1.0F - t) + remote[2] * t,
        };
        Assert.assertEquals("混合色必须落在两类别色之间（R）",
                (primary[0] + remote[0]) * 0.5F, interpolated[0], 1.0e-6F);
        Assert.assertNotEquals("混合色不得等于主色（旧实现在此会整片落回主色）",
                primary[0], interpolated[0], 1.0e-6F);
        Assert.assertNotEquals("混合色不得等于远端色（说明发生了真实插值）",
                remote[0], interpolated[0], 1.0e-6F);

        // 旧口径反证：片元的 == 比较在插值后无法命中任何类别
        float interpolatedSemantic = ChainPreviewSemanticClass.PRIMARY_LOCAL * (1.0F - t)
                + ChainPreviewSemanticClass.REMOTE_PREDICTED * t;
        Assert.assertNotEquals("插值后的类别值不再是整数（所以 == 不可用）",
                (float) ChainPreviewSemanticClass.REMOTE_PREDICTED, interpolatedSemantic, 0.0F);
        Assert.assertEquals("旧实现会落回主色兜底", ChainPreviewShaderMath.PALETTE_PRIMARY,
                interpolatedSemantic == 2.0F ? ChainPreviewShaderMath.PALETTE_REMOTE
                        : ChainPreviewShaderMath.PALETTE_PRIMARY);
    }

    // ------------------------------------------------------------------ 辅助

    private static float[] rgbOf(int semanticClass) {
        return ChainPreviewShaderMath.semanticColorRgb(semanticClass,
                CONFIG_PRIMARY, CONFIG_SECONDARY, CONFIG_REMOTE, CONFIG_TRUNCATED);
    }

    private static void assertRgb(String label, int semanticClass, int expectedRgb) {
        float[] rgb = rgbOf(semanticClass);
        Assert.assertEquals(label + " 的 R", ChainPreviewShaderMath.colorChannel(expectedRgb, 16), rgb[0], 0.0F);
        Assert.assertEquals(label + " 的 G", ChainPreviewShaderMath.colorChannel(expectedRgb, 8), rgb[1], 0.0F);
        Assert.assertEquals(label + " 的 B", ChainPreviewShaderMath.colorChannel(expectedRgb, 0), rgb[2], 0.0F);
    }

    private static String read(String relativePath) throws Exception {
        Path direct = Paths.get(relativePath);
        if (!Files.isRegularFile(direct)) {
            Path dir = Paths.get("").toAbsolutePath();
            while (dir != null) {
                Path candidate = dir.resolve(relativePath);
                if (Files.isRegularFile(candidate)) {
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                }
                dir = dir.getParent();
            }
        }
        Assert.assertTrue("找不到文件: " + relativePath, Files.isRegularFile(direct));
        return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
    }

    private static String stripComments(String source) {
        return Glsl120StaticChecker.stripComments(source, "src", new ArrayList<Glsl120StaticChecker.Finding>());
    }
}
