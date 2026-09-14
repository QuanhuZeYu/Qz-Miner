package club.heiqi.qz_miner.chain.client.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 顶点属性契约（接口冻结 §A 修订，T51）：声明 aPos(3f) / aAux(4 通道) / aDirection(4 x int8 normalized)。
     *
     * <p>原 §A 还包含「attribute 2 aColor 4 x float32（既有颜色流）」，但着色器从不读取它
     * （颜色由 aAux.semanticClass + uColor* 在顶点阶段决定），编译器因此把它整体优化掉
     * （{@code glGetAttribLocation} 返回 -1）。契约里"保留该槽"的写法与实现长期不符，
     * 还让后端每代白白上传一份 262 KB 级、永不被读取的颜色流。现已从契约与着色器中移除；
     * 该流仅剩 legacy 固定管线消费（其逐顶点 α 是 CPU 烘焙值）。</p>
     *
     * <p>新增的 {@code aDirection} 是「屏幕最小宽度 / 真描边」所需的外扩轴向：Mesh 侧按面法线
     * 逐顶点写入。T51 方案 A 起顶点身份 = (位置, 面)，每顶点恰属一个面、方向恒为单位面法线，
     * 因此不存在零方向顶点；着色器永不从 aPos 推断横向轴——那条路会把长条端点 / junction /
     * 跨轴线段误判并推离原始几何。</p>
     *
     * <p>编码用 {@code 4 x int8} 而非 {@code 3 x float32}：方向只有 6 种取值，归一化 byte 的
     * {@code 127/127 = 1.0} 仍是精确值；4096 目标规模下省 8.25 MB/mesh 的顶点流。</p>
     *
     * <p>槽位编号一律不进入契约：GLSL 1.20 无 layout 限定符，属链接期事实，由
     * ChainPreviewShaderProgram#resolveAttributeLocations 运行时解析。</p>
     */
    @Test
    public void declaresFrozenVertexAttributes() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        Map<String, String> attributes = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert")
                .getAttributes();

        Assert.assertEquals("必须恰好声明 §A 约定的三个属性", 3, attributes.size());
        Assert.assertEquals("vec3", attributes.get("aPos"));
        Assert.assertEquals("vec4", attributes.get("aAux"));
        Assert.assertEquals("vec4", attributes.get("aDirection"));
        Assert.assertNull("aColor 已从 shader 路径移除（§A 修订 T51）", attributes.get("aColor"));
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

    // ------------------------------------------------------------------ 清单对账（T49）

    /**
     * 着色器声明的每个 uniform 必须登记在 Java 侧清单（硬必备或能力型）里。
     *
     * <p>不登记 ⇒ 运行期无人对账：要么字段被编译器优化掉无人发现，要么把「按契约保留但当前关闭」
     * 分支引用的 uniform 当必备，导致整个着色器后端被判不可用（2026-09-13 真机「shader 档什么都不画」）。
     * 这条断言把「清单漂移」变成 CI 红灯。</p>
     */
    @Test
    public void everyDeclaredUniformIsRegisteredForReadiness() throws IOException {
        Set<String> registered = new HashSet<String>();
        for (String name : ChainPreviewShaderProgram.requiredUniforms()) {
            registered.add(name);
        }
        for (String name : ChainPreviewShaderProgram.capabilityUniforms()) {
            registered.add(name);
        }
        List<String> unregistered = new ArrayList<String>();
        collectUnregistered("preview.vert", VERTEX_PATH, registered, unregistered);
        collectUnregistered("preview.frag", FRAGMENT_PATH, registered, unregistered);
        Assert.assertTrue("着色器声明的 uniform 必须登记在必备或能力清单里：" + unregistered,
                unregistered.isEmpty());
    }

    private static void collectUnregistered(String label, String path, Set<String> registered,
            List<String> unregistered) throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        for (String name : GlslSourceScanner.of(read(path), ignored, label).getUniforms().keySet()) {
            if (!registered.contains(name)) {
                unregistered.add(label + ":" + name);
            }
        }
    }

    /** 语义调色板 uniform 必须声明在**顶点**着色器（选色在顶点阶段完成，F1）。 */
    @Test
    public void semanticPaletteLivesInVertexStage() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        Map<String, String> vertexUniforms = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert")
                .getUniforms();
        Map<String, String> fragmentUniforms = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag")
                .getUniforms();

        for (String required : new String[] {
                "uColorPrimary", "uColorSecondary", "uColorRemote", "uColorTruncated" }) {
            Assert.assertTrue("顶点必须声明语义色 uniform: " + required, vertexUniforms.containsKey(required));
        }
        for (String forbidden : new String[] {
                "uColorPrimary", "uColorSecondary", "uColorRemote", "uColorTruncated" }) {
            Assert.assertFalse("片元不得再声明 " + forbidden + "（避免两处真源分叉）",
                    fragmentUniforms.containsKey(forbidden));
        }
        Assert.assertFalse("不得保留无人写入的 *Enabled uniform（死 uniform）",
                vertexUniforms.containsKey("uColorPrimaryEnabled"));

        String selector = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert").body("previewSemanticColor");
        Assert.assertTrue("选色必须实现为顶点侧独立函数", selector.length() > 0);
        Assert.assertTrue("主色必须作为兜底返回（0/4/5/255 都走它）",
                selector.indexOf("return uColorPrimary;") >= 0);
        Assert.assertTrue("子模式类别必须走 uColorSecondary",
                selector.indexOf("semanticClass == 1.0") >= 0 && selector.indexOf("return uColorSecondary;") >= 0);
        Assert.assertTrue("远端类别必须走 uColorRemote",
                selector.indexOf("semanticClass == 2.0") >= 0 && selector.indexOf("return uColorRemote;") >= 0);
        Assert.assertTrue("截断类别必须走 uColorTruncated（保留合法分支）",
                selector.indexOf("semanticClass == 3.0") >= 0 && selector.indexOf("return uColorTruncated;") >= 0);
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
     * S1：顶点阶段不得预乘 alpha（rgb 必须是语义色本身，不含 alpha 因子）。
     *
     * <p>共用混合为 {@code GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA}：若 rgb 已乘 alpha，
     * 混合阶段会再乘一次，得到 {@code rgb × alpha²}（alpha=0.15 → 0.0225 vs 0.15），
     * 远距条柱在 shader 档几乎不可见，与 legacy 观感分叉。</p>
     */
    @Test
    public void vertexStageDoesNotPremultiplyAlpha() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        String mainBody = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert").body("main");

        Assert.assertTrue("vColor.rgb 必须来自顶点选色（F1）",
                mainBody.indexOf("vec3 color = previewSemanticColor(auxChannel(aAux.x));") >= 0);
        Assert.assertTrue("vColor 的 rgb/alpha 必须分开写（rgb 不含 alpha）",
                mainBody.indexOf("vColor = vec4(color, alpha);") >= 0);
        Assert.assertFalse("顶点阶段不得把 alpha 乘进 rgb（预乘会让共用 blend 产生 alpha²）",
                mainBody.indexOf("color * alpha") >= 0);
    }

    /**
     * 两阶段表达式必须严格互补（改写一边必失败）：顶点写 vColor.rgb=语义色，
     * 片元读 vColor.rgb 且不再自行选色；片元也不重乘 alpha。
     */
    @Test
    public void vertexAndFragmentColorExpressionsAreComplementary() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        String vertexMain = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert").body("main");
        String fragmentMain = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag").body("main");

        Assert.assertTrue("顶点必须把选好的语义色写进 vColor.rgb",
                vertexMain.indexOf("vec3 color = previewSemanticColor(auxChannel(aAux.x));") >= 0
                        && vertexMain.indexOf("vColor = vec4(color, alpha);") >= 0);
        Assert.assertTrue("片元必须直接输出插值后的 vColor.rgb",
                fragmentMain.indexOf("gl_FragColor = vec4(vColor.rgb, vColor.a)") >= 0);
        Assert.assertFalse("片元不得再乘 vColor.a（会变成预乘）", fragmentMain.indexOf("* vColor.a") >= 0);
        Assert.assertFalse("片元不得自行选色（会与顶点选色形成第二真源）",
                fragmentMain.indexOf("uColor") >= 0);
    }

    /**
     * S2：语义调色板必须被主路径真实消费（不得留从不调用的死 uniform）。
     *
     * <p>F1 之后选择器在顶点：由 {@code main()} 调用 {@code previewSemanticColor}，
     * 结果写进 vColor.rgb；片元只做插值输出，不再持有调色板。</p>
     */
    @Test
    public void semanticPaletteIsConsumedByVertexMain() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        String vertexMain = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert").body("main");

        Assert.assertTrue("顶点主路径必须调用语义色选择器（否则 uColor* 全是死 uniform）",
                GlslSourceScanner.countIdentifier(vertexMain, "previewSemanticColor") > 0);
        Assert.assertTrue("选中颜色必须写进 vColor.rgb",
                vertexMain.indexOf("vColor = vec4(color, alpha);") >= 0);
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

    // ------------------------------------------------------------------ T48c-A 显式相机矩阵

    /**
     * T48c-A：顶点着色器不得再引用固定管线内建矩阵，必须改用显式 uniform。
     *
     * <p>真机（GTNH 2.9 + Angelica 2.2.10 的 GLSM 用生成着色器模拟固定管线 +
     * {@code use_no_error_g_l_context=true}）下，内建 {@code gl_ModelViewProjectionMatrix} /
     * {@code gl_ModelViewMatrix} 与真实相机矩阵失同步，整条预览链被画进错误空间
     * （77px 窄竖条），且失败完全不可观测。因此这里把「源码里不再出现这两个名字」做成硬断言：
     * 注释已由 {@link Glsl120StaticChecker#stripComments} 剔除，断言只针对真实代码。</p>
     */
    @Test
    public void vertexStageUsesExplicitCameraMatricesOnly() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        String code = Glsl120StaticChecker.stripComments(read(VERTEX_PATH), "preview.vert", ignored);
        for (String builtin : new String[] {
                "gl_ModelViewProjectionMatrix", "gl_ModelViewMatrix", "gl_ProjectionMatrix", "ftransform" }) {
            Assert.assertFalse("不得再引用固定管线内建 " + builtin + "（真机环境下与真实相机矩阵失同步）",
                    code.indexOf(builtin) >= 0);
        }

        GlslSourceScanner vertex = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert");
        Assert.assertEquals("MVP 必须是 mat4 uniform", "mat4", vertex.getUniforms().get("uModelViewProjection"));
        Assert.assertEquals("modelview 必须是 mat4 uniform", "mat4", vertex.getUniforms().get("uModelView"));

        String mainBody = vertex.body("main");
        Assert.assertTrue("gl_Position 必须用显式 MVP 投影 displaced",
                mainBody.indexOf("gl_Position = uModelViewProjection * vec4(displaced, 1.0)") >= 0);
        Assert.assertTrue("深度必须取自显式 modelview",
                mainBody.indexOf("-(uModelView * vec4(aPos, 1.0)).z") >= 0);
        // T51 起位移方向来自 aDirection 属性，顶点阶段不再推导横向轴，
        // 「横向投影长度取自显式 modelview」的断言对象已不存在；深度换算仍由上一行断言覆盖。
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
