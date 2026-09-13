package club.heiqi.qz_miner.chain.client.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

/**
 * 预览着色器的资源契约测试。
 *
 * <p>三类断言，全部是「能被证伪的实现契约」而不是源码字符串快照：</p>
 * <ol>
 *   <li><b>资源合法</b>：两个 GLSL 文件通过 {@link Glsl120StaticChecker} 的全部静态检查
 *       （#version 120 首行、括号配平、句法终结、内建参数个数、内建变量表、属性类型）；</li>
 *   <li><b>校验器自身有效</b>：把带错误的最小源码喂给校验器，必须被逐条检出——否则第 1 类断言
 *       只是「永远通过的空壳」；</li>
 *   <li><b>接口冻结 §A/§F 的形状</b>：属性三元组、varying 一致性、片元颜色来自 uniform、
 *       语义 255 兜底主色、生长读 appearOrder、最小宽度受 uniform 门控。</li>
 * </ol>
 */
public class ChainPreviewShaderContractTest {

    private static final String VERTEX_PATH = "src/main/resources/assets/qz_miner/shaders/preview.vert";
    private static final String FRAGMENT_PATH = "src/main/resources/assets/qz_miner/shaders/preview.frag";

    // ------------------------------------------------------------------ 资源合法

    @Test
    public void vertexShaderPassesGlsl120StaticChecks() throws IOException {
        assertNoErrors(VERTEX_PATH, Glsl120StaticChecker.check(read(VERTEX_PATH), "preview.vert"));
    }

    @Test
    public void fragmentShaderPassesGlsl120StaticChecks() throws IOException {
        assertNoErrors(FRAGMENT_PATH, Glsl120StaticChecker.check(read(FRAGMENT_PATH), "preview.frag"));
    }

    /** 顶点与片元的 varying 必须完全一致：名字与类型都相同，否则链接期必然失败。 */
    @Test
    public void varyingsAreIdenticalAcrossStages() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        Map<String, String> vertexVaryings = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert")
                .getVaryings();
        Map<String, String> fragmentVaryings = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag")
                .getVaryings();

        Assert.assertFalse("顶点着色器必须向片元传递 varying", vertexVaryings.isEmpty());
        Assert.assertEquals("varying 名字集合必须一致", vertexVaryings.keySet(), fragmentVaryings.keySet());
        for (Map.Entry<String, String> entry : vertexVaryings.entrySet()) {
            Assert.assertEquals("varying " + entry.getKey() + " 类型必须一致",
                    entry.getValue(), fragmentVaryings.get(entry.getKey()));
        }
    }

    // ------------------------------------------------------------------ 接口冻结 §A/§F

    /** 属性 0/1/2 与接口冻结 §A 一致：aPos(3f) / aAux(4 通道) / aColor(4f)。 */
    @Test
    public void declaresFrozenVertexAttributeTriple() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        Map<String, String> attributes = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert")
                .getAttributes();

        Assert.assertEquals("必须恰好声明 §A 约定的三个属性", 3, attributes.size());
        Assert.assertEquals("vec3", attributes.get("aPos"));
        Assert.assertEquals("vec4", attributes.get("aAux"));
        Assert.assertEquals("vec4", attributes.get("aColor"));
    }

    /**
     * 生长比较必须读 aAux 的 appearOrder（z/w 两个字节），且必须受 uAnimProgress 门控。
     *
     * <p>Lead 裁定：逐波生长只在 shader 路径生效，逐顶点比较 appearOrder &le; cursor，
     * 不要求索引有序；uAnimProgress &ge; 1 时整段可见，完全不读 appearOrder。</p>
     */
    @Test
    public void growthReadsAppearOrderFromAuxBytes() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner vertex = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert");
        String mainBody = vertex.body("main");

        Assert.assertTrue("必须读取 aAux", GlslSourceScanner.countIdentifier(mainBody, "aAux") > 0);
        Assert.assertTrue("必须读取出现序号低字节 aAux.z",
                GlslSourceScanner.countIdentifier(mainBody, "aAux.z") > 0);
        Assert.assertTrue("必须读取出现序号高字节 aAux.w",
                GlslSourceScanner.countIdentifier(mainBody, "aAux.w") > 0);
        Assert.assertTrue("必须存在生长进度 uniform", vertex.getUniforms().containsKey("uAnimProgress"));
        Assert.assertTrue("必须存在目标总数 uniform",
                vertex.getUniforms().containsKey("uAppearSpan"));
        Assert.assertTrue("生长必须被 uAnimProgress < 1.0 门控（=1 时整段绘制）",
                mainBody.indexOf("uAnimProgress < 1.0") >= 0);
        Assert.assertTrue("0xFFFF（未定义序号）必须走「已出现」分支，不得被当成最大序号",
                mainBody.indexOf("appearOrder < 65535.0") >= 0);
        Assert.assertTrue("判据必须是序号格之差（cell 式，Lead 裁定方案 a）",
                mainBody.indexOf("floor(min(appearOrder, uAppearSpan))") >= 0
                        && mainBody.indexOf("uAnimProgress * uAppearSpan - orderFloor") >= 0);
        Assert.assertFalse("uAnimSpan 已是死 uniform，必须删除（Lead 裁定第 2 条）",
                vertex.getUniforms().containsKey("uAnimSpan"));
    }

    /** 片元颜色必须来自 uniform（语义色），并且语义主色与 255 未定义都要落到同一兜底。 */
    @Test
    public void fragmentColorComesFromUniformSemanticPalette() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner fragment = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag");
        Map<String, String> uniforms = fragment.getUniforms();

        for (String required : new String[] {
                "uColorPrimary", "uColorSecondary", "uColorRemote", "uColorTruncated",
                "uColorPrimaryEnabled", "uColorSecondaryEnabled", "uColorRemoteEnabled", "uColorTruncatedEnabled" }) {
            Assert.assertTrue("片元必须声明语义色 uniform: " + required, uniforms.containsKey(required));
        }

        String selector = fragment.body("selectSemanticColor");
        Assert.assertTrue("语义色选择必须实现为独立函数", selector.length() > 0);
        Assert.assertTrue("语义色必须以主色为默认值（255/未定义走主色兜底）",
                selector.indexOf("vec3 color = uColorPrimary") >= 0);
        Assert.assertTrue("四个类别必须逐个 select（GLSL 1.20 不允许非常量下标访问 uniform 数组）",
                selector.indexOf("uColorSecondaryEnabled") > 0 && selector.indexOf("uColorRemoteEnabled") > 0
                        && selector.indexOf("uColorTruncatedEnabled") > 0);
    }

    /** 片元必须真的输出颜色，且 alpha 来自顶点阶段（vColor.a）。 */
    @Test
    public void fragmentWritesBlendedFragColorFromVertexAlpha() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner fragment = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag");
        String mainBody = fragment.body("main");

        Assert.assertTrue("必须写 gl_FragColor", GlslSourceScanner.countIdentifier(mainBody, "gl_FragColor") > 0);
        Assert.assertTrue("alpha 必须取顶点阶段的 vColor.a（片元不重算距离）",
                GlslSourceScanner.countIdentifier(mainBody, "vColor.a") >= 2);
        Assert.assertFalse("片元不得重复声明距离淡出参数，否则曲线会被算两遍",
                fragment.getUniforms().containsKey("uFadeStart"));
        Assert.assertFalse("片元不得重复声明距离淡出参数",
                fragment.getUniforms().containsKey("uFadeEnd"));
    }

    /** 屏幕最小宽度必须由 uMinScreenWidthPx 门控，=0 时整段退化为恒等（用户回退四开关之一）。 */
    @Test
    public void minScreenWidthClampIsGatedByUniform() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner vertex = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert");
        String mainBody = vertex.body("main");

        Assert.assertTrue("必须声明 uMinScreenWidthPx", vertex.getUniforms().containsKey("uMinScreenWidthPx"));
        Assert.assertTrue("钳制必须以 uMinScreenWidthPx > 0 为门槛",
                mainBody.indexOf("uMinScreenWidthPx > 0.0") >= 0);
        Assert.assertTrue("必须声明 uPixelScale（视口像素换算）",
                vertex.getUniforms().containsKey("uPixelScale"));
    }

    // ------------------------------------------------------------------ S1/S2 回归（Lead 冻结前裁定）

    /**
     * S1：顶点阶段不得预乘 alpha。
     *
     * <p>共用混合为 {@code GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA}，预乘后片元再输出 rgb
     * 会得到 {@code rgb × alpha²}（alpha=0.15 → 0.0225 vs 0.15），远距条柱在 shader 档
     * 几乎不可见，与 legacy 观感分叉。</p>
     */
    @Test
    public void vertexStageDoesNotPremultiplyAlpha() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        String mainBody = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert").body("main");

        Assert.assertTrue("vColor.rgb 必须来自顶点基色 aColor.rgb",
                mainBody.indexOf("vColor = vec4(aColor.rgb,") >= 0);
        Assert.assertFalse("顶点阶段不得把 alpha 乘进 rgb（预乘会让共用 blend 产生 alpha²）",
                mainBody.indexOf("aColor.rgb * alpha") >= 0
                        || mainBody.indexOf("aColor.rgb * ") >= 0);
    }

    /**
     * S2：语义色 uniform 必须被主路径真实消费（不得留从不调用的死代码）。
     */
    @Test
    public void semanticPaletteIsActuallyConsumedByFragmentMain() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner fragment = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag");
        String mainBody = fragment.body("main");

        Assert.assertTrue("主路径必须调用语义色选择器（否则 uColor* 全是死 uniform）",
                GlslSourceScanner.countIdentifier(mainBody, "selectSemanticColor") > 0);
        Assert.assertTrue("最终颜色必须直接取语义绝对色：gl_FragColor = vec4(selectSemanticColor(), vColor.a)",
                mainBody.indexOf("vec4(selectSemanticColor(), vColor.a)") >= 0);
        Assert.assertFalse("片元不得再乘顶点基色 vColor.rgb（会与语义色二次乘色：0.25→0.0625、0.9→0.81）",
                mainBody.indexOf("selectSemanticColor() * vColor.rgb") >= 0);
    }

    /**
     * S1+S2 的组合式：片元输出必须是非预乘的（语义色 × 基色 + 独立 alpha）。
     */
    @Test
    public void fragmentOutputStaysNonPremultiplied() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        String mainBody = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag").body("main");

        Assert.assertTrue("alpha 通道必须直接取 vColor.a",
                mainBody.indexOf("vColor.a)") >= 0);
        Assert.assertFalse("rgb 不得乘 vColor.a（会变成预乘，混合后 alpha²）",
                mainBody.indexOf("* vColor.a") >= 0);
        Assert.assertFalse("rgb 不得乘 vColor.rgb（会与语义色二次乘色）",
                mainBody.indexOf("* vColor.rgb") >= 0);
    }

    // ------------------------------------------------------------------ 校验器自证（负例）

    @Test
    public void checkerRejectsMissingVersionDirective() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "void main(void) {\n    gl_Position = ftransform();\n}\n", "bad.vert"), "#version");
    }

    @Test
    public void checkerRejectsWrongGlslVersion() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 330\nvoid main(void) {\n    gl_Position = ftransform();\n}\n", "bad.vert"), "120");
    }

    @Test
    public void checkerRejectsUnbalancedBraces() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    gl_Position = ftransform();\n", "bad.vert"), "未闭合");
    }

    @Test
    public void checkerRejectsExtensionOnlyAndModernBuiltins() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    float w = fwidth(1.0);\n}\n", "bad.vert"), "fwidth");
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    gl_FragColor = texture(tex, uv);\n}\n", "bad.frag"),
                "texture(");
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    float id = float(gl_VertexID);\n}\n", "bad.vert"),
                "gl_VertexID");
    }

    @Test
    public void checkerRejectsIntegerAttributeTypes() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nattribute int aId;\nvoid main(void) {\n    gl_Position = vec4(0.0);\n}\n", "bad.vert"),
                "属性必须是浮点类型");
    }

    @Test
    public void checkerRejectsUnknownBuiltinVariableSpelling() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    gl_Position = gl_ModleViewMatrix * vec4(1.0);\n}\n", "bad.vert"),
                "gl_ModleViewMatrix");
    }

    @Test
    public void checkerRejectsWrongBuiltinArity() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    float v = clamp(1.0, 2.0);\n}\n", "bad.vert"), "clamp");
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    float v = smoothstep(0.0, 1.0);\n}\n", "bad.vert"),
                "smoothstep");
    }

    @Test
    public void checkerRejectsUnterminatedStatement() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    float v = 1.0\n    gl_Position = vec4(v);\n}\n", "bad.vert"),
                "终结符");
    }

    @Test
    public void checkerAcceptsLegalMinimalShaderWithoutFalsePositives() {
        String legal = "#version 120\n"
                + "attribute vec3 aPos;\n"
                + "uniform float uScale;\n"
                + "varying float vOut;\n"
                + "float helper(float value) {\n"
                + "    return clamp(value * uScale, 0.0, 1.0);\n"
                + "}\n"
                + "void main(void) {\n"
                + "    vOut = helper(aPos.x);\n"
                + "    gl_Position = ftransform();\n"
                + "}\n";
        assertNoErrors("legal.vert", Glsl120StaticChecker.check(legal, "legal.vert"));
    }

    // ------------------------------------------------------------------ 辅助

    private static void assertNoErrors(String fileName, List<Glsl120StaticChecker.Finding> findings) {
        List<String> errors = new ArrayList<String>();
        for (Glsl120StaticChecker.Finding finding : findings) {
            if ("error".equals(finding.severity)) {
                errors.add(finding.toString());
            }
        }
        Assert.assertTrue(fileName + " 存在静态错误: " + errors, errors.isEmpty());
    }

    private static void assertHasErrorContaining(List<Glsl120StaticChecker.Finding> findings, String needle) {
        for (Glsl120StaticChecker.Finding finding : findings) {
            if ("error".equals(finding.severity) && finding.message.contains(needle)) {
                return;
            }
        }
        Assert.fail("期望检出包含「" + needle + "」的错误，实际: " + findings);
    }

    private static String read(String relativePath) throws IOException {
        Path path = Glsl120StaticChecker.resolve(relativePath);
        Assert.assertTrue("找不到着色器资源: " + relativePath, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
