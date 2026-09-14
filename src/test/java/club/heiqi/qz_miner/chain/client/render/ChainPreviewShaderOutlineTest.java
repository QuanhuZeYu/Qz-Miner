package club.heiqi.qz_miner.chain.client.render;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

import org.junit.Assert;
import org.junit.Test;

/**
 * B3.x 真描边（着色器扩边）的契约。
 *
 * <p>本项最硬的判据是「默认档不受影响」：</p>
 * <ul>
 *   <li><b>xray（默认）/ occlude</b>：{@code uOutlineWidthPx = 0}，顶点位移必须恒等 ⇒ 逐值等于现状；</li>
 *   <li><b>OUTLINE 主体 pass</b>：同样 {@code = 0}，走完全同现状的路径；</li>
 *   <li>只有 <b>OUTLINE 描边壳 pass</b> 传 &gt; 0，沿既有 lateralAxis 横向机制外扩。</li>
 * </ul>
 *
 * <p>其余约束：不依赖 tubeEdge 四象限（实测只落 {0,1}）、取色仍在顶点阶段、
 * fade × growth × uFadeAlpha 包络不被描边分支破坏、宽度 0/负/超限必须收敛。</p>
 */
public class ChainPreviewShaderOutlineTest {

    private static final String VERTEX_PATH = "src/main/resources/assets/qz_miner/shaders/preview.vert";
    private static final String FRAGMENT_PATH = "src/main/resources/assets/qz_miner/shaders/preview.frag";
    private static final String BACKEND_PATH =
            "src/main/java/club/heiqi/qz_miner/chain/client/render/ChainPreviewShaderBackend.java";

    /** plan 契约里的默认描边宽度（1.5 物理像素，Lead 批准，不新增配置键）。 */
    private static final float DEFAULT_OUTLINE_PX = ChainPreviewDrawPlan.OUTLINE_WIDTH_DEFAULT_PX;

    /** 默认条柱厚度（与 ChainPreviewMeshBuilder 的默认一致；决定描边世界上界）。 */
    private static final float DEFAULT_BAR_THICKNESS = 0.045F;

    // ------------------------------------------------------------------ 三档等价回归

    /** 关闭描边（xray / occlude / 主体 pass）时外扩量必须精确为 0 ⇒ 位移矩阵恒等。 */
    @Test
    public void disabledOutlineProducesExactlyZeroWidening() {
        float[] pixelsPerUnitSamples = {0.001F, 0.1F, 1.0F, 10.0F, 900.0F, 10000.0F};
        for (float pixelsPerUnit : pixelsPerUnitSamples) {
            Assert.assertEquals("widthPx=0 必须精确不外扩（pixelsPerUnit=" + pixelsPerUnit + "）",
                    0.0F, ChainPreviewShaderMath.outlineWidenWorld(
                            0.0F, pixelsPerUnit, DEFAULT_BAR_THICKNESS), 0.0F);
            Assert.assertFalse("widthPx=0 必须判定为关闭",
                    ChainPreviewShaderMath.isOutlineEnabled(0.0F));
        }
    }

    /** GLSL：所有描边分支必须由 uOutlineWidthPx > 0 门控，否则默认档会被污染。 */
    @Test
    public void everyOutlineBranchIsGatedByPositiveWidth() throws Exception {
        String body = stripComments(read(VERTEX_PATH));

        Assert.assertTrue("必须声明 uOutlineWidthPx", body.contains("uniform float uOutlineWidthPx;"));
        // T51 后位移方向一律来自 aDirection 属性，顶点阶段不再推导「横向轴」：
        // 最小宽度与描边是两处独立位移，各自以 > 0.0 门控（描边位移 + 描边配色共两处门控）。
        Assert.assertTrue("最小宽度位移必须以 > 0 门控",
                body.contains("if (uMinScreenWidthPx > 0.0) {"));
        int firstOutlineGate = body.indexOf("if (uOutlineWidthPx > 0.0) {");
        Assert.assertTrue("描边位移分支必须以 > 0 门控", firstOutlineGate >= 0);
        Assert.assertTrue("描边色分支必须以 > 0 门控",
                body.indexOf("if (uOutlineWidthPx > 0.0) {", firstOutlineGate + 1) >= 0);
        Assert.assertTrue("描边位移必须沿显式面方向 aDirection",
                body.contains("displaced + aDirection.xyz *"));
        Assert.assertFalse("不得出现「非正即启用」的反向判定",
                body.contains("uOutlineWidthPx >= 0.0"));
    }

    /** 主体着色路径必须与现状逐式一致：alpha 表达式里不得出现描边量。 */
    @Test
    public void outlineDoesNotTouchAlphaEnvelope() throws Exception {
        String body = stripComments(read(VERTEX_PATH));
        Assert.assertTrue("alpha 包络必须仍是 fade × growth × uFadeAlpha",
                body.contains("float alpha = fade * growth * uFadeAlpha;"));
        Assert.assertFalse("包络不得掺入描边量",
                body.contains("alpha = fade * growth * uFadeAlpha * uOutlineWidthPx"));
        Assert.assertFalse("亮度/alpha 不得被描边宽度调制",
                body.contains("uOutlineWidthPx * fade") || body.contains("fade * uOutlineWidthPx"));
    }

    // ------------------------------------------------------------------ 壳段外扩量映射

    /** px → world 换算必须与 minScreenWidth 同口径（1 世界单位 = pixelsPerWorldUnit 像素）。 */
    @Test
    public void shellWideningConvertsPixelsToWorld() {
        float thickness = DEFAULT_BAR_THICKNESS;
        Assert.assertEquals("1.5px @ 900px/单位 = 1.5/900 世界",
                DEFAULT_OUTLINE_PX / 900.0F,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 900.0F, thickness), 1.0e-7F);
        // 采样必须避开世界上限（默认厚度下为 0.455）：1.5px @ 10px/单位 = 0.15 世界，未触顶
        Assert.assertEquals("1.5px @ 10px/单位 = 0.15 世界",
                DEFAULT_OUTLINE_PX / 10.0F,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 10.0F, thickness), 1.0e-7F);
        // 单调：像素/单位越大（越远），同一像素宽度对应越小的世界外扩
        float near = ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 10.0F, thickness);
        float far = ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 1000.0F, thickness);
        Assert.assertTrue("越远外扩越小", far < near);
    }

    // ------------------------------------------------------------------ 宽度边界收敛

    /** 宽度 0 / 负 / NaN 一律收敛为关闭（精确 0）。 */
    @Test
    public void nonPositiveWidthCollapsesToDisabled() {
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(0.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(-1.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(-999.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidthPx(Float.NaN), 0.0F);
        Assert.assertFalse(ChainPreviewShaderMath.isOutlineEnabled(-2.0F));
        Assert.assertEquals("负宽度外扩量必须为 0",
                0.0F, ChainPreviewShaderMath.outlineWidenWorld(-5.0F, 900.0F, DEFAULT_BAR_THICKNESS), 0.0F);
    }

    // ------------------------------------------------------------------ F-1：外扩上界与相邻不重叠

    /**
     * F-1（cross-review 取证）：世界上界必须是 {@code 0.5 − barThickness}，不是固定 0.5。
     *
     * <p>固定 0.5 在默认厚度 0.045 下会让相邻条柱重叠 0.09 格（中心距 1 格、两侧同时外扩）。</p>
     */
    @Test
    public void widenCapSubtractsBarThickness() {
        Assert.assertEquals("厚度 0.045 ⇒ 上界 0.455",
                0.455F, ChainPreviewShaderMath.maxOutlineWorld(0.045F), 1.0e-7F);
        Assert.assertEquals("厚度 0.2 ⇒ 上界 0.3",
                0.3F, ChainPreviewShaderMath.maxOutlineWorld(0.2F), 1.0e-7F);
        Assert.assertEquals("厚度 0.005（下限）⇒ 上界 0.495",
                0.495F, ChainPreviewShaderMath.maxOutlineWorld(0.005F), 1.0e-7F);
        Assert.assertEquals("厚度 >= 0.5（极端）⇒ 上界收敛 0",
                0.0F, ChainPreviewShaderMath.maxOutlineWorld(0.5F), 0.0F);
        Assert.assertEquals("厚度 0.99 ⇒ 仍为 0（不得为负）",
                0.0F, ChainPreviewShaderMath.maxOutlineWorld(0.99F), 0.0F);
        Assert.assertEquals("NaN 厚度 ⇒ 最保守 0",
                0.0F, ChainPreviewShaderMath.maxOutlineWorld(Float.NaN), 0.0F);
    }

    /** 上界必须真的作用在换算上：请求超大宽度 ⇒ 恰好落在 0.455（默认厚度）。 */
    @Test
    public void widenWorldSaturatesAtThicknessDependentCap() {
        float thickness = DEFAULT_BAR_THICKNESS;
        float saturated = ChainPreviewShaderMath.outlineWidenWorld(1.0e6F, 0.0001F, thickness);
        Assert.assertEquals("巨大请求必须收敛到上界", 0.455F, saturated, 1.0e-6F);
        Assert.assertFalse("不得为 NaN", Float.isNaN(saturated));
    }

    /** F-1 的验收核心：默认厚度 + 最大外扩时，相邻条柱（中心距 1 格）不得重叠。 */
    @Test
    public void neighboursDoNotOverlapAtMaximumWidening() {
        for (float thickness : new float[] {0.005F, 0.045F, 0.1F, 0.2F}) {
            float cap = ChainPreviewShaderMath.maxOutlineWorld(thickness);
            // 请求远超上界 ⇒ 实际取到上界
            float widen = ChainPreviewShaderMath.outlineWidenWorld(1.0e6F, 0.0001F, thickness);
            Assert.assertEquals("厚度 " + thickness + " 时必须取到上界", cap, widen, 1.0e-6F);

            float gap = ChainPreviewShaderMath.neighbourGap(thickness, widen);
            Assert.assertTrue("厚度 " + thickness + " 在上界处不得重叠，实际间隙=" + gap, gap >= -1.0e-6F);
        }

        // 反证：旧口径（固定 0.5）在默认厚度下确实重叠
        float legacyGap = ChainPreviewShaderMath.neighbourGap(DEFAULT_BAR_THICKNESS, 0.5F);
        Assert.assertEquals("旧固定 0.5 的间隙应为 -0.09（重叠）", -0.09F, legacyGap, 1.0e-6F);
        Assert.assertTrue("旧口径确实重叠", legacyGap < 0.0F);
    }

    /** GLSL 侧必须是同一式子（用已有 uniform uBarThickness，不引入新 uniform）。 */
    @Test
    public void glslUsesSameThicknessDependentCap() throws Exception {
        String vertex = stripComments(read(VERTEX_PATH));
        // T51 后描边世界量直接在位移表达式中以内层 min 收窄，上界子式保持不变
        Assert.assertTrue("GLSL 上界必须减去 uBarThickness",
                vertex.contains("max(0.0, 0.5 - uBarThickness)"));
        Assert.assertFalse("不得再出现固定 0.5 的旧上界",
                vertex.contains("clamp(outlineWorld, 0.0, 0.5)"));
        Assert.assertTrue("必须复用既有 uBarThickness（不新增 uniform）",
                vertex.contains("uniform float uBarThickness;"));
    }

    /**
     * F-2 登记：真描边是着色器路径专有，auto 档回退 legacy 时退化为两 pass 叠色。
     *
     * <p>登记写在源文件注释里，因此这里检查<strong>未剥注释</strong>的原文
     * （其余断言用剥注释后的代码，避免注释里的示例污染结构判定）。</p>
     */
    @Test
    public void legacyCapabilityGapIsDocumented() throws Exception {
        String raw = read(VERTEX_PATH);
        Assert.assertTrue("必须登记 legacy 能力差异", raw.contains("能力差异"));
        Assert.assertTrue("必须点名 legacy 回退行为", raw.contains("回退 legacy"));
        Assert.assertTrue("必须说明原因（固定管线外扩要改 CPU 几何）",
                raw.contains("固定管线") && raw.contains("CPU 几何"));
    }

    /** 超限宽度必须收敛到上限（像素侧 8px；世界侧 0.5 格）。 */
    @Test
    public void oversizedWidthIsClampedOnBothSides() {
        Assert.assertEquals("像素侧收敛到 8",
                ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderMath.outlineWidthPx(1000.0F), 0.0F);
        Assert.assertEquals("刚好等于上限时保持",
                ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderMath.outlineWidthPx(ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX), 0.0F);

        // 极近视角：1px 对应很大世界量 ⇒ 世界侧必须被「0.5 − 厚度」封顶（F-1）
        Assert.assertEquals("世界侧收敛到 0.5 − barThickness",
                ChainPreviewShaderMath.maxOutlineWorld(DEFAULT_BAR_THICKNESS),
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 0.0001F, DEFAULT_BAR_THICKNESS), 0.0F);

        // 非法 pixelsPerWorldUnit 不得产生 NaN / 无穷
        float thickness = DEFAULT_BAR_THICKNESS;
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 0.0F, thickness), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, -1.0F, thickness), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, Float.NaN, thickness), 0.0F);
        Assert.assertFalse("不得产生 NaN",
                Float.isNaN(ChainPreviewShaderMath.outlineWidenWorld(
                        DEFAULT_OUTLINE_PX, Float.POSITIVE_INFINITY, thickness)));
    }

    // ------------------------------------------------------------------ 既有契约不被破坏

    /** 不依赖 tubeEdge 四象限：描边与取色都不得读 aAux.y。 */
    @Test
    public void outlineDoesNotDependOnTubeEdgeQuadrants() throws Exception {
        String vertex = stripComments(read(VERTEX_PATH));
        Assert.assertFalse("顶点不得读 aAux.y（实测只落 {0,1}，四象限不齐备）",
                vertex.contains("aAux.y"));
        String fragment = stripComments(read(FRAGMENT_PATH));
        Assert.assertFalse("片元不得读 aAux.y", fragment.contains("aAux.y"));
    }

    /** 取色仍在顶点阶段（F1）：片元不得出现 uColor* 或类别判定。 */
    @Test
    public void semanticSelectionStaysInVertexStage() throws Exception {
        String vertex = stripComments(read(VERTEX_PATH));
        Assert.assertTrue("顶点必须调用 previewSemanticColor",
                vertex.contains("previewSemanticColor(auxChannel(aAux.x))"));
        String fragment = stripComments(read(FRAGMENT_PATH));
        Assert.assertFalse("片元不得声明 uColor*", fragment.contains("uniform vec3 uColor"));
        Assert.assertFalse("片元不得做类别比较", fragment.contains("vSemantic"));
    }

    /** 描边壳用统一主色，而不是按类别分色（轮廓应可辨识、不参与语义分类）。 */
    @Test
    public void outlineShellUsesPrimaryColor() throws Exception {
        String vertex = stripComments(read(VERTEX_PATH));
        Assert.assertTrue("描边壳必须用 uColorPrimary",
                vertex.contains("color = uColorPrimary;"));
    }

    /** 后端必须真的消费 plan 的描边面：只有壳段传非 0，其余精确为 0。 */
    @Test
    public void backendConsumesOutlineShellOnly() throws Exception {
        String body = stripComments(read(BACKEND_PATH));
        Assert.assertTrue("必须读 plan 的壳段标志", body.contains("plan.isOutlineShell()"));
        Assert.assertTrue("必须读 plan 的描边宽度", body.contains("plan.getOutlineWidthPx()"));
        Assert.assertTrue("非壳段必须传 0（矩阵恒等）",
                body.contains("plan.isOutlineShell() ? plan.getOutlineWidthPx() : 0.0F"));
        Assert.assertTrue("宽度必须经 host 侧收敛后再进 uniform",
                body.contains("ChainPreviewShaderMath.outlineWidthPx(outlineWidthPx)"));
    }

    /** 常量单一真源：参考模型的像素上限必须引用 plan，而不是自己写一份。 */
    @Test
    public void outlineWidthCapHasSingleSourceOfTruth() {
        Assert.assertEquals("上限必须等于 plan 的契约值",
                ChainPreviewDrawPlan.MAX_OUTLINE_WIDTH_PX,
                ChainPreviewShaderMath.MAX_OUTLINE_WIDTH_PX, 0.0F);
        Assert.assertEquals("plan 默认宽度必须是 1.5px", 1.5F,
                ChainPreviewDrawPlan.OUTLINE_WIDTH_DEFAULT_PX, 0.0F);
    }

    /** 描边只改顶点位移，不改拓扑：不得出现索引/几何分支。 */
    @Test
    public void outlineOnlyDisplacesVertices() throws Exception {
        String vertex = stripComments(read(VERTEX_PATH));
        Assert.assertTrue("外扩必须加到 displaced 上（沿显式面方向 aDirection）",
                vertex.contains("displaced = displaced + aDirection.xyz *"));
        Assert.assertFalse("顶点着色器不得触碰索引",
                vertex.contains("gl_VertexID") || vertex.contains("gl_Index"));
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
