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

    // ------------------------------------------------------------------ 三档等价回归

    /** 关闭描边（xray / occlude / 主体 pass）时外扩量必须精确为 0 ⇒ 位移矩阵恒等。 */
    @Test
    public void disabledOutlineProducesExactlyZeroWidening() {
        float[] pixelsPerUnitSamples = {0.001F, 0.1F, 1.0F, 10.0F, 900.0F, 10000.0F};
        for (float pixelsPerUnit : pixelsPerUnitSamples) {
            Assert.assertEquals("widthPx=0 必须精确不外扩（pixelsPerUnit=" + pixelsPerUnit + "）",
                    0.0F, ChainPreviewShaderMath.outlineWidenWorld(0.0F, pixelsPerUnit), 0.0F);
            Assert.assertFalse("widthPx=0 必须判定为关闭",
                    ChainPreviewShaderMath.isOutlineEnabled(0.0F));
        }
    }

    /** GLSL：所有描边分支必须由 uOutlineWidthPx > 0 门控，否则默认档会被污染。 */
    @Test
    public void everyOutlineBranchIsGatedByPositiveWidth() throws Exception {
        String body = stripComments(read(VERTEX_PATH));

        Assert.assertTrue("必须声明 uOutlineWidthPx", body.contains("uniform float uOutlineWidthPx;"));
        // 描边相关的三处判定都必须是 > 0.0 门控
        Assert.assertTrue("横向轴计算必须把描边计入启用条件",
                body.contains("uMinScreenWidthPx > 0.0 || uOutlineWidthPx > 0.0"));
        Assert.assertTrue("外扩分支必须以 > 0 门控",
                body.contains("if (uOutlineWidthPx > 0.0 && lateralMagnitude > 0.0)"));
        Assert.assertTrue("描边色分支必须以 > 0 门控",
                body.contains("if (uOutlineWidthPx > 0.0) {"));
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
        Assert.assertEquals("1.5px @ 900px/单位 = 1.5/900 世界",
                DEFAULT_OUTLINE_PX / 900.0F,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 900.0F), 1.0e-7F);
        // 采样必须避开世界上限（0.5 格）：1.5px @ 10px/单位 = 0.15 世界，未触顶
        Assert.assertEquals("1.5px @ 10px/单位 = 0.15 世界",
                DEFAULT_OUTLINE_PX / 10.0F,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 10.0F), 1.0e-7F);
        // 单调：像素/单位越大（越远），同一像素宽度对应越小的世界外扩
        float near = ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 10.0F);
        float far = ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 1000.0F);
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
                0.0F, ChainPreviewShaderMath.outlineWidenWorld(-5.0F, 900.0F), 0.0F);
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

        // 极近视角：1px 对应很大世界量 ⇒ 世界侧必须被 0.5 格封顶
        Assert.assertEquals("世界侧收敛到 0.5",
                ChainPreviewShaderMath.MAX_OUTLINE_WORLD,
                ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 0.0001F), 0.0F);

        // 非法 pixelsPerWorldUnit 不得产生 NaN / 无穷
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, 0.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, -1.0F), 0.0F);
        Assert.assertEquals(0.0F, ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, Float.NaN), 0.0F);
        Assert.assertFalse("不得产生 NaN",
                Float.isNaN(ChainPreviewShaderMath.outlineWidenWorld(DEFAULT_OUTLINE_PX, Float.POSITIVE_INFINITY)));
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
        Assert.assertTrue("外扩必须加到 displaced 上",
                vertex.contains("displaced = displaced + lateralAxis * outlineWorld;"));
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
