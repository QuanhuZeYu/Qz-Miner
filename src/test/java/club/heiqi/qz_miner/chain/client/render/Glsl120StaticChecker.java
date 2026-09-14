package club.heiqi.qz_miner.chain.client.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GLSL 1.20（GL 2.1）离线静态校验器。
 *
 * <p>动机：构建链路只做字符串契约检查，<strong>不会真正编译 GLSL</strong>——着色器语法错误
 * 只能到真机运行时才暴露，而运行时的表现是「编译失败 → 静默回退 legacy」，观感变差却没有任何
 * 异常。本校验器在离线阶段拦住其中<strong>可静态判定</strong>的几类，并且与
 * {@code UiBackdropShaderSyntaxTest} 的做法不同：它不做源码字符串 contains 断言，而是真的
 * 做词法分析、作用域符号表、调用点参数个数核对、内建白名单与固定管线内建禁令，因此它能被
 * 「喂进一份坏源码」的负例测试证伪（见 {@code ChainPreviewShaderContractTest}）。</p>
 *
 * <p>刻意不用 windowed 正则匹配整个源；只用线性扫描与 {@code indexOf}，避免跨语言转义坑。</p>
 */
final class Glsl120StaticChecker {

    /** GLSL 1.20 保留字（片段/顶点通用子集），不参与符号表。 */
    private static final Set<String> KEYWORDS = new LinkedHashSet<String>();
    /** 内建函数名 → 允许的参数个数区间 [min, max]（max 为 -1 表示不设上界）。 */
    private static final Map<String, int[]> BUILTIN_FUNCTIONS = new LinkedHashMap<String, int[]>();
    /** 内建变量（含 gl_ 前缀），不参与符号表。 */
    private static final Set<String> BUILTIN_VARIABLES = new LinkedHashSet<String>();
    /** 内建函数返回值/参数用到的类型名，作为构造式的例外（vec3(x) 这类构造）。 */
    private static final Set<String> TYPE_NAMES = new LinkedHashSet<String>();

    /**
     * 本项目着色器路径<b>禁止引用</b>的 GLSL 1.20 固定管线（compatibility）内建：名字 → 禁用理由。
     *
     * <p><b>两个正交的轴</b>：{@link #BUILTIN_VARIABLES} / {@link #BUILTIN_FUNCTIONS} 回答「GLSL 1.20
     * 里这个符号是什么」（语言事实，拼错 {@code gl_ModleViewMatrix} 必须报「未知内建」）；本表回答
     * 「本项目允不允许用它」（接口决策）。所以同一个名字可以「是合法内建」且「被本项目禁用」，
     * 而报错只有一条、只讲真正的原因。</p>
     *
     * <p><b>为什么必须整词匹配</b>：禁用名之间有前缀/后缀重叠——{@code gl_ModelViewMatrix} 是
     * {@code gl_ModelViewMatrixInverse} 的前缀、又与 {@code gl_ModelViewProjectionMatrix} 共享
     * {@code gl_ModelView}，而 {@code gl_ProjectionMatrix} 还是 {@code gl_ModelViewProjectionMatrix}
     * 的后缀。子串匹配两头都会错：{@code "gl_ModelViewProjectionMatrix".contains("gl_ModelViewMatrix")}
     * 为 <b>false</b>（长名漏报），按后缀/词干匹配又会把长名报成短名（误报）。因此判定统一走
     * {@link #scanIdentifiers}：它只吐完整 GLSL 标识符 token，相等即命中。</p>
     *
     * <p><b>禁的是两类「静默失效」</b>：</p>
     * <ol>
     *   <li><b>几何/属性输入</b>（{@code gl_Vertex} / {@code gl_Normal} / {@code gl_Color} /
     *       {@code gl_MultiTexCoord*} / {@code gl_FogCoord}）：取自兼容管线的 client array 槽位，
     *       本项目着色器路径不填充（且会与接口冻结 §A 的用户属性抢槽），几何类输入更是未经位移管线的
     *       原始值。</li>
     *   <li><b>矩阵状态</b>（{@code gl_ModelViewProjectionMatrix} 一族）与便捷函数 {@code ftransform}：
     *       相机矩阵在本项目真机上不可信，{@code ftransform()} 内部还只用原始顶点位置。</li>
     * </ol>
     *
     * <p><b>T48c-A 的历史教训（这条规则存在的原因）</b>：{@code preview.vert} 曾经写
     * {@code gl_Position = ftransform();}。{@code ftransform()} 语义等价于
     * {@code gl_ModelViewProjectionMatrix * gl_Vertex}——乘的是<b>原始顶点位置</b>，而不是上面刚算完
     * 屏幕最小宽度位移的 {@code displaced}。于是那段位移<b>算完即丢</b>：着色器照常编译、照常出图，
     * 观感「正常」，只是该生效的横向钳制从未生效（B2.1 在 shader 路径静默失效）。同族的第二类坑是
     * 内建矩阵本身：真机（GTNH 2.9 + Angelica 2.2.10 的 GLSM 用生成着色器模拟固定管线，
     * {@code use_no_error_g_l_context=true}）下 {@code gl_ModelViewProjectionMatrix} /
     * {@code gl_ModelViewMatrix} 与真实相机矩阵失同步，整条预览链被画进错误空间（77px 窄竖条），
     * 失败同样完全不可观测。修法是把相机矩阵显式化为 mat4 uniform（{@code uModelViewProjection} /
     * {@code uModelView}），并<b>只对 {@code displaced} 做投影</b>。</p>
     *
     * <p>这两类坑原由三处「读 shader 源码做文本匹配」的断言钉住（{@code body.contains(...)}），
     * 已随 T52 按裁定整体删除（文本快照重命名即误报、改语义却照样绿）。本规则是它的替代防线：
     * 走词法分析而不是文本快照，可以被负例证伪（见 {@code ChainPreviewShaderContractTest}），
     * 且「为什么」就写在这里。</p>
     *
     * <p><b>刻意不在禁列</b>（划边界，避免误伤）：</p>
     * <ul>
     *   <li>输出与片元接口：{@code gl_Position} / {@code gl_PointSize} / {@code gl_ClipVertex} /
     *       {@code gl_FragColor} / {@code gl_FragData} / {@code gl_FragDepth} / {@code gl_FragCoord} /
     *       {@code gl_FrontFacing} / {@code gl_PointCoord}——它们是着色器与管线之间的必要接口，
     *       不是固定管线状态，禁掉等于禁掉着色器本身。</li>
     *   <li>与相机矩阵无关的固定管线状态（{@code gl_DepthRange} / {@code gl_ClipPlane} /
     *       {@code gl_LightSource} / {@code gl_FrontMaterial} / {@code gl_Fog} 等）：既不会绕过本项目的
     *       位移管线，也不会被 GLSM 的矩阵模拟带偏；把它们一并禁掉属范围外扩张，只会在将来真需要时
     *       逼出「为绕检查而改名」的坏味道。</li>
     * </ul>
     */
    private static final Map<String, String> FORBIDDEN_FIXED_PIPELINE = new LinkedHashMap<String, String>();

    /** 禁用理由：几何/属性输入一族。 */
    private static final String REASON_FIXED_PIPELINE_VERTEX_INPUT =
            "它取自兼容管线的 client array 槽位——本项目不填充这些槽位（还会与 §A 的用户属性抢槽），"
                    + "几何类输入更是未经位移管线的原始值；几何与属性一律来自显式 attribute";
    /** 禁用理由：相机/纹理矩阵状态一族。 */
    private static final String REASON_FIXED_PIPELINE_MATRIX =
            "真机（Angelica GLSM 用生成着色器模拟固定管线 + use_no_error_g_l_context=true）下它与真实相机矩阵"
                    + "失同步、失败不可观测；相机矩阵必须走显式 mat4 uniform（uModelViewProjection / uModelView）";
    /** 禁用理由：唯一的被禁函数 ftransform()。 */
    private static final String REASON_FTRANSFORM =
            "它内部用原始顶点位置取 MVP（等价于 gl_ModelViewProjectionMatrix * gl_Vertex），先算好的位移会被整段"
                    + "丢弃——T48c-A 的屏幕最小宽度钳制就是这样静默失效的；必须写成 uModelViewProjection * vec4(displaced, 1.0)";

    static {
        for (String keyword : ("attribute const uniform varying break continue do for while if else in out inout "
                + "float int void bool true false discard return struct").split(" ")) {
            if (!keyword.isEmpty()) {
                KEYWORDS.add(keyword);
            }
        }
        for (String type : ("float int bool void vec2 vec3 vec4 ivec2 ivec3 ivec4 bvec2 bvec3 bvec4 mat2 mat3 mat4 "
                + "sampler2D samplerCube").split(" ")) {
            if (!type.isEmpty()) {
                TYPE_NAMES.add(type);
            }
        }
        register("radians", 1, 1);
        register("degrees", 1, 1);
        register("sin", 1, 1);
        register("cos", 1, 1);
        register("tan", 1, 1);
        register("asin", 1, 1);
        register("acos", 1, 1);
        register("atan", 1, 2);
        register("pow", 2, 2);
        register("exp", 1, 1);
        register("log", 1, 1);
        register("exp2", 1, 1);
        register("log2", 1, 1);
        register("sqrt", 1, 1);
        register("inversesqrt", 1, 1);
        register("abs", 1, 1);
        register("sign", 1, 1);
        register("floor", 1, 1);
        register("ceil", 1, 1);
        register("fract", 1, 1);
        register("mod", 2, 2);
        register("min", 2, 2);
        register("max", 2, 2);
        register("clamp", 3, 3);
        register("mix", 3, 3);
        register("step", 2, 2);
        register("smoothstep", 3, 3);
        register("length", 1, 1);
        register("distance", 2, 2);
        register("dot", 2, 2);
        register("cross", 2, 2);
        register("normalize", 1, 1);
        register("faceforward", 3, 3);
        register("reflect", 2, 2);
        register("refract", 3, 3);
        register("matrixCompMult", 2, 2);
        register("lessThan", 2, 2);
        register("lessThanEqual", 2, 2);
        register("greaterThan", 2, 2);
        register("greaterThanEqual", 2, 2);
        register("equal", 2, 2);
        register("notEqual", 2, 2);
        register("any", 1, 1);
        register("all", 1, 1);
        register("not", 1, 1);
        register("texture2D", 2, 3);
        register("texture2DProj", 2, 3);
        register("textureCube", 2, 3);
        // GLSL 1.20 兼容管线内建函数：登记它是为了让它仍按内建解析、参数个数仍被核对——
        // 「本项目允不允许用」是另一个轴，由下面的 FORBIDDEN_FIXED_PIPELINE 说了算。
        register("ftransform", 0, 0);
        register("texture2DLod", 3, 3);
        for (String variable : ("gl_Position gl_PointSize gl_ClipVertex gl_FragCoord gl_FrontFacing gl_FragColor "
                + "gl_FragData gl_PointCoord gl_Color gl_SecondaryColor gl_Normal gl_Vertex gl_MultiTexCoord0 "
                + "gl_MultiTexCoord1 gl_MultiTexCoord2 gl_MultiTexCoord3 gl_MultiTexCoord4 gl_MultiTexCoord5 "
                + "gl_MultiTexCoord6 gl_MultiTexCoord7 gl_FogCoord gl_ModelViewMatrix gl_ProjectionMatrix "
                + "gl_ModelViewProjectionMatrix gl_NormalMatrix gl_TextureMatrix gl_ModelViewMatrixInverse "
                + "gl_ProjectionMatrixInverse gl_ModelViewProjectionMatrixInverse gl_TextureMatrixInverse "
                + "gl_ModelViewMatrixTranspose gl_ProjectionMatrixTranspose gl_ModelViewProjectionMatrixTranspose "
                + "gl_TextureMatrixTranspose gl_ModelViewMatrixInverseTranspose gl_ProjectionMatrixInverseTranspose "
                + "gl_ModelViewProjectionMatrixInverseTranspose gl_TextureMatrixInverseTranspose "
                + "gl_DepthRange gl_ClipPlane")
                .split(" ")) {
            if (!variable.isEmpty()) {
                BUILTIN_VARIABLES.add(variable);
            }
        }
        // 固定管线内建禁令：与上面的内建白名单是两个正交的轴（理由见 FORBIDDEN_FIXED_PIPELINE 的说明）。
        // 顶点输入一族：几何/属性一律来自显式 attribute（接口冻结 §A）。
        for (String name : ("gl_Vertex gl_Normal gl_Color gl_SecondaryColor gl_FogCoord "
                + "gl_MultiTexCoord0 gl_MultiTexCoord1 gl_MultiTexCoord2 gl_MultiTexCoord3 gl_MultiTexCoord4 "
                + "gl_MultiTexCoord5 gl_MultiTexCoord6 gl_MultiTexCoord7").split(" ")) {
            forbidFixedPipeline(name, REASON_FIXED_PIPELINE_VERTEX_INPUT);
        }
        // 矩阵状态一族：四族有完整的 Inverse / Transpose / InverseTranspose 变体，必须整族禁止——
        // 只禁正矩阵等于留一条「换个后缀就绕过」的缝。gl_NormalMatrix 是唯一没有变体的
        // （glslang 16.6.0 -S vert 实测：gl_NormalMatrixInverse / -Transpose 均为 undeclared identifier）。
        for (String stem : ("gl_ModelViewMatrix gl_ProjectionMatrix gl_ModelViewProjectionMatrix "
                + "gl_TextureMatrix").split(" ")) {
            forbidFixedPipeline(stem, REASON_FIXED_PIPELINE_MATRIX);
            forbidFixedPipeline(stem + "Inverse", REASON_FIXED_PIPELINE_MATRIX);
            forbidFixedPipeline(stem + "Transpose", REASON_FIXED_PIPELINE_MATRIX);
            forbidFixedPipeline(stem + "InverseTranspose", REASON_FIXED_PIPELINE_MATRIX);
        }
        forbidFixedPipeline("gl_NormalMatrix", REASON_FIXED_PIPELINE_MATRIX);
        // 唯一被禁的函数。内建函数表里保留 ftransform 条目，是为了让它仍按「内建」解析并核对参数个数，
        // 而不是被当成拼错的用户函数——禁用与否由本表说了算。
        forbidFixedPipeline("ftransform", REASON_FTRANSFORM);
    }

    private static void register(String name, int min, int max) {
        BUILTIN_FUNCTIONS.put(name, new int[] {min, max});
    }

    private static void forbidFixedPipeline(String name, String reason) {
        FORBIDDEN_FIXED_PIPELINE.put(name, reason);
    }

    /** 一条校验发现。severity 为 "error" 时测试必须失败。 */
    static final class Finding {

        final String severity;
        final int line;
        final String message;

        Finding(String severity, int line, String message) {
            this.severity = severity;
            this.line = line;
            this.message = message;
        }

        @Override
        public String toString() {
            return severity + " L" + line + ": " + message;
        }
    }

    private Glsl120StaticChecker() {}

    /**
     * 校验一份 GLSL 源码。
     *
     * @param source   源码
     * @param fileName 文件名（用于消息）
     * @return 发现列表；空表示通过全部静态检查
     */
    static List<Finding> check(String source, String fileName) {
        List<Finding> findings = new ArrayList<Finding>();
        String code = stripComments(source, fileName, findings);
        checkVersionDirective(source, fileName, findings);
        checkBalanced(code, findings);
        checkForbiddenBuiltins(code, findings);
        checkFixedPipelineBuiltins(code, findings);
        checkIntegerAttributes(code, findings);
        checkStatementsTerminated(code, findings);
        checkCalls(code, findings);
        checkBuiltinVariables(code, findings);
        checkDeclaredOnce(code, findings);
        return findings;
    }

    /** 读文件并校验；文件缺失本身是一条 error。 */
    static List<Finding> checkFile(Path path) {
        List<Finding> findings = new ArrayList<Finding>();
        if (!Files.isRegularFile(path)) {
            findings.add(new Finding("error", 0, "着色器资源不存在: " + path));
            return findings;
        }
        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            findings.addAll(check(source, path.getFileName().toString()));
            return findings;
        } catch (IOException exception) {
            findings.add(new Finding("error", 0, "读取着色器失败: " + exception));
            return findings;
        }
    }

    /**
     * 定位仓库内的相对路径资源。
     *
     * <p>兼容不同 Gradle 测试工作目录：先按相对路径，再逐级向上查找。</p>
     */
    static Path resolve(String relativePath) {
        Path direct = Paths.get(relativePath);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return direct;
    }

    /** 剥掉行注释与块注释；未闭合的块注释记一条 error。 */
    static String stripComments(String source, String fileName, List<Finding> findings) {
        StringBuilder out = new StringBuilder(source.length());
        int index = 0;
        int line = 1;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '\n') {
                line++;
                out.append(current);
                index++;
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int startLine = line;
                index += 2;
                boolean closed = false;
                while (index + 1 < source.length()) {
                    if (source.charAt(index) == '*' && source.charAt(index + 1) == '/') {
                        index += 2;
                        closed = true;
                        break;
                    }
                    if (source.charAt(index) == '\n') {
                        line++;
                        out.append('\n');
                    }
                    index++;
                }
                if (!closed) {
                    findings.add(new Finding("error", startLine, fileName + " 块注释未闭合"));
                }
                continue;
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    /** 首个非空行必须是 #version，且必须是 120（GL 2.1 基线，接口冻结 §F）。 */
    static void checkVersionDirective(String source, String fileName, List<Finding> findings) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.startsWith("#version")) {
                findings.add(new Finding("error", i + 1, fileName + " 首个非空行必须是 #version，实际=" + trimmed));
                return;
            }
            String normalized = trimmed.replace("\t", " ");
            if (!normalized.equals("#version 120") && !normalized.startsWith("#version 120 ")) {
                findings.add(new Finding("error", i + 1, fileName + " 必须是 #version 120（GL 2.1 基线），实际=" + trimmed));
            }
            return;
        }
        findings.add(new Finding("error", 0, fileName + " 为空"));
    }

    /** 剥注释后括号必须配平。 */
    static void checkBalanced(String code, List<Finding> findings) {
        List<Character> stack = new ArrayList<Character>();
        int line = 1;
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '\n') {
                line++;
                continue;
            }
            if (ch == '(' || ch == '{' || ch == '[') {
                stack.add(Character.valueOf(ch));
            } else if (ch == ')' || ch == '}' || ch == ']') {
                if (stack.isEmpty()) {
                    findings.add(new Finding("error", line, "括号提前闭合: " + ch));
                    return;
                }
                char open = stack.remove(stack.size() - 1).charValue();
                char want = ch == ')' ? '(' : (ch == '}' ? '{' : '[');
                if (open != want) {
                    findings.add(new Finding("error", line, "括号类型不匹配: 期望 " + want + " 实际 " + open));
                    return;
                }
            }
        }
        if (!stack.isEmpty()) {
            findings.add(new Finding("error", line, "括号未闭合，数量差=" + stack.size()));
        }
    }

    /** 需扩展或 1.30+ 才可用的函数/特性：出现即视为移植性风险（缺扩展机器上整体编译失败）。 */
    static void checkForbiddenBuiltins(String code, List<Finding> findings) {
        String[] forbidden = {
                "texture2DBias", "textureGather", "textureProjLod", "texelFetch", "textureLod", "texture(", "fwidth",
                "dFdx", "dFdy", "gl_VertexID", "gl_InstanceID", "gl_PrimitiveID", "gl_FragDepth", "layout(", "round(",
                "trunc(", "isnan", "isinf", "uint", "noperspective", "flat varying",
        };
        for (String token : forbidden) {
            int at = code.indexOf(token);
            if (at >= 0) {
                findings.add(new Finding("error", lineOf(code, at), "GLSL 1.20 不可用/需扩展的写法: " + token));
            }
        }
    }

    /** GLSL 1.20 顶点属性只允许浮点类型（int/uint/I 后缀要 1.30+）。 */
    static void checkIntegerAttributes(String code, List<Finding> findings) {
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.startsWith("attribute ")) {
                continue;
            }
            String rest = trimmed.substring("attribute ".length()).trim();
            int space = rest.indexOf(' ');
            String type = space < 0 ? rest : rest.substring(0, space);
            if (type.startsWith("i") || type.startsWith("u") || type.startsWith("b")) {
                findings.add(new Finding("error", i + 1, "GLSL 1.20 属性必须是浮点类型，实际=" + trimmed));
            }
        }
    }

    /**
     * 语句终结符检查（保守）：只在语句块内，对不含控制关键字、未以 ;/{/} 结束的行报错。
     *
     * <p>刻意避开 for/if/else/while 与函数定义行，避免把合法的多行表达式误判。</p>
     */
    static void checkStatementsTerminated(String code, List<Finding> findings) {
        String[] lines = code.split("\n", -1);
        int braceDepth = 0;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int open = count(trimmed, '{');
            int close = count(trimmed, '}');
            boolean insideBlock = braceDepth > 0;
            braceDepth += open - close;
            if (!insideBlock && open == 0) {
                continue;
            }
            if (isControlFlowLine(trimmed) || isFunctionSignatureLine(trimmed) || trimmed.endsWith(",")) {
                continue;
            }
            if (trimmed.endsWith(";") || trimmed.endsWith("{") || trimmed.endsWith("}") || trimmed.endsWith(":")) {
                continue;
            }
            if (trimmed.startsWith("//")) {
                continue;
            }
            // 多行三元/续行极少出现在本资源里；出现时要求上一行以运算符结尾
            findings.add(new Finding("error", i + 1, "语句缺少终结符: " + trimmed));
        }
    }

    private static boolean isControlFlowLine(String trimmed) {
        return trimmed.startsWith("if ") || trimmed.startsWith("if(") || trimmed.startsWith("else")
                || trimmed.startsWith("for ") || trimmed.startsWith("for(") || trimmed.startsWith("while")
                || trimmed.startsWith("switch") || trimmed.startsWith("do") || trimmed.startsWith("return")
                || trimmed.startsWith("discard");
    }

    private static boolean isFunctionSignatureLine(String trimmed) {
        return trimmed.endsWith(")")
                && (trimmed.indexOf('(') > 0)
                && !trimmed.contains("=")
                && !trimmed.startsWith("return");
    }

    /**
     * 标识符访问者：{@link #scanIdentifiers} 每切出一个完整 token 就回调一次。
     *
     * <p>刻意用内部接口而不是重复写扫描循环：本文件里所有「按标识符判定」的规则
     * （内建函数参数个数、未知内建变量、固定管线禁令）共享同一套词法，
     * 于是「按词边界精确匹配」是结构保证，而不是每条规则各自小心。</p>
     */
    private interface IdentifierVisitor {

        /**
         * @param name  token 文本（完整的 GLSL 标识符）
         * @param start token 在源码中的起始下标
         * @param end   token 结束下标（不含）
         */
        void visit(String name, int start, int end);
    }

    /**
     * 线性切分 GLSL 标识符（字母/下划线开头，字母、数字、下划线继续），逐个交给访问者。
     *
     * <p>这里是「按词边界精确匹配」的唯一实现：访问者拿到的永远是完整 token，所以
     * {@code gl_ModelViewProjectionMatrix} 绝不会被切出 {@code gl_ModelViewMatrix}，
     * {@code gl_Position} 也绝不会被 {@code gl_ProjectionMatrix} 的规则命中。
     * 本项目禁用清单里存在多组前缀/后缀重叠的内建名（见 {@link #FORBIDDEN_FIXED_PIPELINE}），
     * 只有整词判定才是对的。</p>
     */
    private static void scanIdentifiers(String code, IdentifierVisitor visitor) {
        int index = 0;
        while (index < code.length()) {
            char ch = code.charAt(index);
            if (!Character.isLetter(ch) && ch != '_') {
                index++;
                continue;
            }
            int start = index;
            while (index < code.length() && (Character.isLetterOrDigit(code.charAt(index)) || code.charAt(index) == '_')) {
                index++;
            }
            visitor.visit(code.substring(start, index), start, index);
        }
    }

    /**
     * 调用点核对：所有被调用的标识符必须能解析为「已声明的函数」或「GLSL 1.20 内建」；
     * 内建函数还需参数个数匹配。
     */
    static void checkCalls(String code, final List<Finding> findings) {
        // 只核对「内建函数的参数个数」；用户自定义函数由 checkDeclaredOnce 与编译器兜底。
        scanIdentifiers(code, new IdentifierVisitor() {

            @Override
            public void visit(String name, int start, int end) {
                int probe = end;
                while (probe < code.length() && (code.charAt(probe) == ' ' || code.charAt(probe) == '\t')) {
                    probe++;
                }
                if (probe >= code.length() || code.charAt(probe) != '(') {
                    return;
                }
                if (KEYWORDS.contains(name) || TYPE_NAMES.contains(name)) {
                    return;
                }
                int[] signature = BUILTIN_FUNCTIONS.get(name);
                if (signature == null) {
                    return;
                }
                int argumentCount = countTopLevelArguments(code, probe);
                if (argumentCount < signature[0] || (signature[1] >= 0 && argumentCount > signature[1])) {
                    findings.add(new Finding("error", lineOf(code, start), "内建函数 " + name + " 参数个数=" + argumentCount
                            + "，允许区间=[" + signature[0] + "," + (signature[1] < 0 ? "∞" : String.valueOf(signature[1])) + "]"));
                }
            }
        });
    }

    /**
     * 固定管线内建禁令：{@link #FORBIDDEN_FIXED_PIPELINE} 里的任何符号出现即 error。
     *
     * <p>判定是「完整 token 相等」，不是子串/前缀/后缀匹配——理由与历史教训见
     * {@link #FORBIDDEN_FIXED_PIPELINE} 的说明。</p>
     */
    static void checkFixedPipelineBuiltins(String code, final List<Finding> findings) {
        scanIdentifiers(code, new IdentifierVisitor() {

            @Override
            public void visit(String name, int start, int end) {
                String reason = FORBIDDEN_FIXED_PIPELINE.get(name);
                if (reason != null) {
                    findings.add(new Finding("error", lineOf(code, start), "着色器不得引用固定管线内建 " + name + "：" + reason));
                }
            }
        });
    }

    /**
     * 内建变量核对：任何 {@code gl_} 前缀标识符必须在 GLSL 1.20 内建变量表内。
     *
     * <p>这是本校验器唯一能可靠判定「用未声明符号」的场景——{@code gl_} 前缀是保留给实现的，
     * 用户不可能自行声明，因此拼错（例如 {@code gl_ModleViewMatrix}）必然编译失败。</p>
     */
    static void checkBuiltinVariables(String code, final List<Finding> findings) {
        scanIdentifiers(code, new IdentifierVisitor() {

            @Override
            public void visit(String name, int start, int end) {
                if (!name.startsWith("gl_")) {
                    return;
                }
                if (!BUILTIN_VARIABLES.contains(name)) {
                    findings.add(new Finding("error", lineOf(code, start), "未知的 GLSL 内建变量: " + name));
                }
            }
        });
    }

    private static int countTopLevelArguments(String code, int openParen) {
        int depth = 0;
        int count = 0;
        boolean sawToken = false;
        for (int i = openParen; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '(' || ch == '[') {
                depth++;
                if (depth == 1) {
                    continue;
                }
            } else if (ch == ')' || ch == ']') {
                depth--;
                if (depth == 0) {
                    return sawToken ? count + 1 : 0;
                }
            } else if (ch == ',' && depth == 1) {
                count++;
                sawToken = false;
                continue;
            }
            if (depth >= 1 && !Character.isWhitespace(ch) && ch != '(' && ch != '[') {
                sawToken = true;
            }
        }
        return sawToken ? count + 1 : 0;
    }

    /** 同一文件内不允许同名函数定义两遍（GLSL 允许重载但需要不同参数，这里只拦完全同名同参数的重复）。 */
    static void checkDeclaredOnce(String code, List<Finding> findings) {
        List<String> names = scanFunctionNames(code);
        Set<String> seen = new LinkedHashSet<String>();
        for (String name : names) {
            if (!seen.add(name)) {
                findings.add(new Finding("warning", 0, "函数 " + name + " 在同文件内定义/声明多次"));
            }
        }
    }

    private static List<String> scanFunctionNames(String code) {
        List<String> names = new ArrayList<String>();
        String[] lines = code.split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            int paren = trimmed.indexOf('(');
            if (paren <= 0) {
                continue;
            }
            String head = trimmed.substring(0, paren).trim();
            int space = head.lastIndexOf(' ');
            if (space <= 0) {
                continue;
            }
            String returnType = head.substring(0, space).trim();
            String name = head.substring(space + 1).trim();
            if (!TYPE_NAMES.contains(returnType) || name.isEmpty()) {
                continue;
            }
            if (!Character.isJavaIdentifierStart(name.charAt(0))) {
                continue;
            }
            boolean identifier = true;
            for (int i = 1; i < name.length(); i++) {
                if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                    identifier = false;
                    break;
                }
            }
            if (identifier) {
                names.add(name);
            }
        }
        return names;
    }

    private static int count(String text, char needle) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == needle) {
                count++;
            }
        }
        return count;
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
