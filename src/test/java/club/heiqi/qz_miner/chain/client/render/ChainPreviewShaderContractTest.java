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
 *       （#version 120 首行、括号配平、句法终结、内建参数个数、内建变量表、属性类型、固定管线内建禁令）；</li>
 *   <li><b>校验器自身有效</b>：把带错误的最小源码喂给校验器，必须被逐条检出——否则第 1 类断言
 *       只是「永远通过的空壳」；</li>
 *   <li><b>接口冻结 §A/§F 的形状</b>：{@link GlslSourceScanner} 解析出的<b>声明面</b>
 *       （属性/varying/uniform/函数的名字与类型：属性三元组、varying 一致性、调色板 uniform 只在
 *       顶点阶段声明、uniform 清单对账）。</li>
 * </ol>
 *
 * <p><b>不再做「读 shader 源码文本匹配」的断言</b>（{@code body.contains(...)} / {@code indexOf}
 * 找语句或表达式）：那种断言重命名变量即误报、改系数/改语义却照样绿，拦不住真问题。GLSL 侧改动的
 * 口径改为「真机验证 + 在 shader 头部「实机验证记录」追加一行标记」，且<strong>注释改动本身不触发
 * 重验</strong>（否则加标记会形成死循环）。声明面解析保留：属性/uniform 名字是 Java 侧按字符串
 * 解析的运行时接口（§A 契约），重命名会真的打断绑定，不是快照。</p>
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
     * 生长语义的<b>声明面</b>契约：进度与目标总数必须是已声明的 uniform，且不得留下死 uniform。
     *
     * <p>Lead 裁定：逐波生长只在 shader 路径生效（uAnimProgress &ge; 1 时整段可见，完全不读
     * appearOrder），判据是「序号格之差」（cell 式）。这些<strong>行为</strong>由纯 JVM 参考模型
     * 逐格数值断言（{@link ChainPreviewShaderMath#growthWeight}，见 ChainPreviewShaderGrowthWidthTest
     * 与 ShaderPathContractTest），GLSL 侧与之一致性由真机验证 + shader 头部标记负责。</p>
     */
    @Test
    public void growthUniformSurfaceIsDeclared() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner vertex = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert");

        Assert.assertTrue("必须存在生长进度 uniform", vertex.getUniforms().containsKey("uAnimProgress"));
        Assert.assertTrue("必须存在目标总数 uniform",
                vertex.getUniforms().containsKey("uAppearSpan"));
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

    /**
     * 语义调色板 uniform 必须声明在**顶点**着色器（选色在顶点阶段完成，F1）。
     *
     * <p>断言只落在声明面（uniform 名字按字符串在运行时解析，属 §A/§D 接口契约）与「选择器实现为
     * 顶点侧独立函数」这一结构事实上；具体表达式（哪一行 return 哪个 uniform）不再做文本匹配。</p>
     */
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
    }

    /**
     * 片元的声明面必须保持最小：距离淡出参数只属于顶点阶段，否则曲线会被算两遍（两处真源分叉）。
     *
     * <p>「片元真的输出插值后的颜色与 alpha」属 GLSL 行为，不再用源码文本断言；由
     * {@link ChainPreviewShaderMath#shaderMixedSource} 的数值契约 + 真机验证 + shader 头部标记覆盖。</p>
     */
    @Test
    public void fragmentStageDoesNotRedeclareFadeUniforms() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner fragment = GlslSourceScanner.of(read(FRAGMENT_PATH), ignored, "preview.frag");

        Assert.assertFalse("片元不得重复声明距离淡出参数，否则曲线会被算两遍",
                fragment.getUniforms().containsKey("uFadeStart"));
        Assert.assertFalse("片元不得重复声明距离淡出参数",
                fragment.getUniforms().containsKey("uFadeEnd"));
    }

    /**
     * 屏幕最小宽度所需的 uniform 必须在顶点阶段声明（名字由 Java 侧按字符串解析并登记为硬必备）。
     *
     * <p>{@code uMinScreenWidthPx = 0} 时位移恒等、{@code > 0} 时单向加宽并交付配置像素宽，这些
     * <strong>行为</strong>由 {@link ChainPreviewShaderMath#minWidthWidenWorld} /
     * {@code displaceVertex} 的数值断言覆盖（ChainPreviewShaderGrowthWidthTest / ShaderPathContractTest）。</p>
     */
    @Test
    public void minScreenWidthUniformsAreDeclared() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner vertex = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert");

        Assert.assertTrue("必须声明 uMinScreenWidthPx", vertex.getUniforms().containsKey("uMinScreenWidthPx"));
        Assert.assertTrue("必须声明 uPixelScale（视口像素换算）",
                vertex.getUniforms().containsKey("uPixelScale"));
    }

    // ------------------------------------------------------------------ T48c-A 显式相机矩阵

    /**
     * T48c-A：相机矩阵必须是**显式 mat4 uniform**（{@code uModelViewProjection} / {@code uModelView}）。
     *
     * <p>真机（GTNH 2.9 + Angelica 2.2.10 的 GLSM 用生成着色器模拟固定管线 +
     * {@code use_no_error_g_l_context=true}）下，固定管线内建 {@code gl_ModelViewProjectionMatrix} /
     * {@code gl_ModelViewMatrix} 与真实相机矩阵失同步，整条预览链会被画进错误空间（77px 窄竖条），
     * 且失败完全不可观测。本测试只钉住<strong>声明面</strong>（两个 mat4 uniform 必须存在，名字由
     * Java 侧按字符串解析、登记为硬必备）；「源码里不再出现内建矩阵名」这类<strong>文本</strong>禁令
     * 已按裁定移除（源码文本快照重命名即误报、改语义却照样绿），现在由
     * {@link Glsl120StaticChecker} 的固定管线内建禁令以<strong>词法规则</strong>承担——喂一份坏源码
     * 进去就会红，见本类的 {@link #checkerRejectsFixedPipelineBuiltins()}。</p>
     */
    @Test
    public void cameraMatrixUniformsAreMat4() throws IOException {
        List<Glsl120StaticChecker.Finding> ignored = new ArrayList<Glsl120StaticChecker.Finding>();
        GlslSourceScanner vertex = GlslSourceScanner.of(read(VERTEX_PATH), ignored, "preview.vert");
        Assert.assertEquals("MVP 必须是 mat4 uniform", "mat4", vertex.getUniforms().get("uModelViewProjection"));
        Assert.assertEquals("modelview 必须是 mat4 uniform", "mat4", vertex.getUniforms().get("uModelView"));
    }

    // ------------------------------------------------------------------ 校验器自证（负例）

    @Test
    public void checkerRejectsMissingVersionDirective() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "void main(void) {\n    gl_Position = vec4(0.0);\n}\n", "bad.vert"), "#version");
    }

    @Test
    public void checkerRejectsWrongGlslVersion() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 330\nvoid main(void) {\n    gl_Position = vec4(0.0);\n}\n", "bad.vert"), "120");
    }

    @Test
    public void checkerRejectsUnbalancedBraces() {
        assertHasErrorContaining(Glsl120StaticChecker.check(
                "#version 120\nvoid main(void) {\n    gl_Position = vec4(0.0);\n", "bad.vert"), "未闭合");
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

    // ------------------------------------------------------------------ 固定管线内建禁令（T53）

    /**
     * 固定管线几何/矩阵内建必须被拒：它们要么绕过已算好的位移，要么在真机上与真实相机矩阵失同步。
     *
     * <p>这是 T48c-A 那三处「读源码做文本匹配」断言被删除后的替代防线——规则本体与历史教训写在
     * {@link Glsl120StaticChecker} 的 {@code FORBIDDEN_FIXED_PIPELINE} 注释里。此处断言的是
     * 「把这行源码喂进校验器会不会红」，不是「源码里有没有某个字符串」：重命名变量、换行、
     * 抽成辅助函数都不影响判定，而把投影改回内建矩阵一定红。</p>
     */
    @Test
    public void checkerRejectsFixedPipelineBuiltins() {
        // 致命项：ftransform() 内部用原始顶点位置取 MVP，先算好的位移会被整段丢弃（T48c-A）。
        assertHasErrorContaining(checkVertex("    gl_Position = ftransform();\n"), "固定管线内建 ftransform：");
        // T48c-A 第一类坑：真机内建矩阵与真实相机矩阵失同步。
        assertHasErrorContaining(checkVertex("    gl_Position = gl_ModelViewProjectionMatrix * vec4(aPos, 1.0);\n"),
                "固定管线内建 gl_ModelViewProjectionMatrix：");
        assertHasErrorContaining(checkVertex("    gl_Position = gl_ModelViewMatrix * vec4(aPos, 1.0);\n"),
                "固定管线内建 gl_ModelViewMatrix：");
        assertHasErrorContaining(checkVertex("    gl_Position = vec4(gl_ProjectionMatrix[0][0]);\n"),
                "固定管线内建 gl_ProjectionMatrix：");
        // 固定管线几何/属性输入：几何与属性一律来自显式 attribute（接口冻结 §A）。
        assertHasErrorContaining(checkVertex("    gl_Position = gl_Vertex;\n"), "固定管线内建 gl_Vertex：");
        assertHasErrorContaining(checkVertex("    gl_Position = vec4(gl_MultiTexCoord0.xy, 0.0, 1.0);\n"),
                "固定管线内建 gl_MultiTexCoord0：");
        // 矩阵变体不得被后缀绕过：禁用清单按族覆盖 Inverse / Transpose / InverseTranspose。
        assertHasErrorContaining(checkVertex("    gl_Position = vec4(gl_ModelViewMatrixInverse[0][0]);\n"),
                "固定管线内建 gl_ModelViewMatrixInverse：");
        assertHasErrorContaining(checkVertex("    gl_Position = vec4(gl_ProjectionMatrixInverseTranspose[0][0]);\n"),
                "固定管线内建 gl_ProjectionMatrixInverseTranspose：");
    }

    /**
     * 重叠的内建名必须按<b>整词</b>判定：每个名字只由自己命中，既不漏报也不冒名。
     *
     * <p>四组用例对应两种真实的错法（名字关系先用 Python 复核过）：<b>漏报</b>——
     * {@code gl_ModelViewMatrix} 与 {@code gl_ModelViewProjectionMatrix} 互不为子串，按短名查会整条
     * 丢掉长名；<b>冒名</b>——{@code gl_ModelViewMatrix} 是 {@code gl_ModelViewMatrixInverse} 的真子串，
     * {@code gl_Normal} 是 {@code gl_NormalMatrix} 的真子串（而且这四个名字都在禁用名单里），子串匹配
     * 会在只写了长名时额外冒出一条短名的错误。</p>
     *
     * <p>断言里带上全角冒号，是为了让「名字」成为消息里的完整一段：否则 {@code "…内建 gl_Normal"}
     * 会成为 {@code "…内建 gl_NormalMatrix"} 的前缀，反误伤断言自己就失效了。</p>
     */
    @Test
    public void fixedPipelineRuleMatchesWholeBuiltinNamesOnly() {
        // 漏报方向：只出现长名时必须由长名自己命中。
        List<Glsl120StaticChecker.Finding> longOnly = checkVertex(
                "    gl_Position = gl_ModelViewProjectionMatrix * vec4(aPos, 1.0);\n");
        assertHasErrorContaining(longOnly, "固定管线内建 gl_ModelViewProjectionMatrix：");
        assertNoErrorContaining(longOnly, "固定管线内建 gl_ModelViewMatrix：");

        // 反方向：只出现短名时也必须命中，且不得冒充长名。
        List<Glsl120StaticChecker.Finding> shortOnly = checkVertex(
                "    gl_Position = gl_ModelViewMatrix * vec4(aPos, 1.0);\n");
        assertHasErrorContaining(shortOnly, "固定管线内建 gl_ModelViewMatrix：");
        assertNoErrorContaining(shortOnly, "固定管线内建 gl_ModelViewProjectionMatrix：");

        // 冒名方向一：gl_ModelViewMatrix ⊂ gl_ModelViewMatrixInverse。
        List<Glsl120StaticChecker.Finding> inverseOnly = checkVertex(
                "    gl_Position = vec4(gl_ModelViewMatrixInverse[0][0]);\n");
        assertHasErrorContaining(inverseOnly, "固定管线内建 gl_ModelViewMatrixInverse：");
        assertNoErrorContaining(inverseOnly, "固定管线内建 gl_ModelViewMatrix：");

        // 冒名方向二（最贴近的陷阱）：gl_Normal ⊂ gl_NormalMatrix，两个名字都在禁用名单里。
        List<Glsl120StaticChecker.Finding> normalMatrixOnly = checkVertex(
                "    gl_Position = vec4(gl_NormalMatrix[0][0]);\n");
        assertHasErrorContaining(normalMatrixOnly, "固定管线内建 gl_NormalMatrix：");
        assertNoErrorContaining(normalMatrixOnly, "固定管线内建 gl_Normal：");
    }

    /**
     * 反误伤：输出与片元内建必须原样通过——它们是着色器与管线之间的必要接口，不是固定管线状态。
     *
     * <p>{@code gl_Position} 与内建矩阵名共享 {@code gl_Pro…} / {@code gl_ModelView…} 前缀，
     * 是最容易被过度匹配误伤的一族；注释里写满禁用名的源也必须绿（剥注释在校验之前完成）。</p>
     */
    @Test
    public void checkerAcceptsOutputAndFragmentBuiltins() {
        assertNoErrors("legal.vert", Glsl120StaticChecker.check(
                "#version 120\n"
                        + "attribute vec3 aPos;\n"
                        + "uniform mat4 uModelViewProjection;\n"
                        + "void main(void) {\n"
                        + "    gl_PointSize = 1.0;\n"
                        + "    gl_Position = uModelViewProjection * vec4(aPos, 1.0);\n"
                        + "}\n",
                "legal.vert"));
        assertNoErrors("legal.frag", Glsl120StaticChecker.check(
                "#version 120\n"
                        + "void main(void) {\n"
                        + "    if (gl_FrontFacing) {\n"
                        + "        gl_FragColor = vec4(gl_FragCoord.xy, 0.0, 1.0);\n"
                        + "    } else {\n"
                        + "        gl_FragColor = vec4(0.0);\n"
                        + "    }\n"
                        + "}\n",
                "legal.frag"));
        // 注释里的禁用名不算引用：preview.vert 头部就写着 ftransform() / gl_ModelViewProjectionMatrix，
        // 它必须仍然通过（第 1 类断言已覆盖真实文件本身）。
        assertNoErrors("legal.vert", Glsl120StaticChecker.check(
                "#version 120\n"
                        + "// 不得写 ftransform() 或 gl_ModelViewProjectionMatrix\n"
                        + "/* gl_ModelViewMatrix：历史写法 */\n"
                        + "void main(void) {\n"
                        + "    gl_Position = vec4(0.0);\n"
                        + "}\n",
                "legal.vert"));
    }

    @Test
    public void checkerAcceptsLegalMinimalShaderWithoutFalsePositives() {
        String legal = "#version 120\n"
                + "attribute vec3 aPos;\n"
                + "uniform float uScale;\n"
                + "uniform mat4 uModelViewProjection;\n"
                + "varying float vOut;\n"
                + "float helper(float value) {\n"
                + "    return clamp(value * uScale, 0.0, 1.0);\n"
                + "}\n"
                + "void main(void) {\n"
                + "    vOut = helper(aPos.x);\n"
                + "    gl_Position = uModelViewProjection * vec4(aPos, 1.0);\n"
                + "}\n";
        assertNoErrors("legal.vert", Glsl120StaticChecker.check(legal, "legal.vert"));
    }

    // ------------------------------------------------------------------ 辅助

    /** 顶点负例的统一外壳：属性声明 + main，只把待检语句留给用例填。 */
    private static List<Glsl120StaticChecker.Finding> checkVertex(String statement) {
        return Glsl120StaticChecker.check(
                "#version 120\nattribute vec3 aPos;\nvoid main(void) {\n" + statement + "}\n", "bad.vert");
    }

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

    private static void assertNoErrorContaining(List<Glsl120StaticChecker.Finding> findings, String needle) {
        for (Glsl120StaticChecker.Finding finding : findings) {
            if ("error".equals(finding.severity) && finding.message.contains(needle)) {
                Assert.fail("不应检出包含「" + needle + "」的错误，实际: " + findings);
            }
        }
    }

    private static String read(String relativePath) throws IOException {
        Path path = Glsl120StaticChecker.resolve(relativePath);
        Assert.assertTrue("找不到着色器资源: " + relativePath, Files.isRegularFile(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
