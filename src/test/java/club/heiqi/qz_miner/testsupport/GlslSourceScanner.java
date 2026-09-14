package club.heiqi.qz_miner.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GLSL 源码的词汇/顶层结构读取入口：剥注释、抽 attribute/varying/uniform 声明面、函数签名与函数体。
 *
 * <p><b>为什么存在</b>：着色器的「声明面」是 Java 侧按字符串解析的运行时接口
 * （{@code glGetUniformLocation} / {@code glGetAttribLocation} 拿名字去问），所以「某个 uniform 到底
 * 声明没有、是 mat4 还是 vec4、只在顶点阶段还是两阶段都有、函数还在不在」是真实契约而不是文本快照；
 * 但它是 GLSL 而不是 Java，{@link JavaSourceSlices} 的 Java 口径（方法体、调用实参、标识符）
 * 在这里没有对应概念。本类原先住在 {@code chain.client.render} 包内且是包私有，
 * {@code chain.client.verify} 的用例够不到，只能各自写正则——已并入 {@code testsupport}，
 * 让任何包都能用同一套词法与结构口径（注释剥离也只有这一份实现：
 * 原包内的 GLSL 校验器改为委托本类，避免两套注释剥离器随版本分叉）。</p>
 *
 * <p><b>能证伪什么</b>：删掉/改名一个 uniform、attribute、varying 或函数（声明面集合立即变化）；
 * 把 uniform 从顶点阶段挪到片元阶段（两阶段集合互换）；改 uniform 类型（如 mat4 写成 vec3）；
 * 两阶段 varying 名字或类型不一致（链接期必然失败）；把代码写进注释冒充声明；
 * 某个 uniform 只声明、从不被函数体引用（{@link #countIdentifier} 的词边界计数为 0）。</p>
 *
 * <p><b>守不到什么</b>：GLSL 语义与行为（类型推导、参数个数、表达式取值、编译器实际是否保留某 uniform
 * 全都不在这里判定，本类只做顶层文本结构抽取）；预处理器分支（{@code #ifdef} 里外的声明一视同仁）；
 * 多行折叠的声明写法（声明必须落在同一行，这是本仓着色器的既有排版约束）；
 * 运行时是否真的绑定成功（真机验证 + shader 头部「实机验证记录」承担）。</p>
 *
 * <p><b>服务哪些用例</b>：{@code ChainPreviewShaderContractTest}（属性三元组、varying 一致性、
 * 阶段归属、uniform 清单对账）、{@code ChainPreviewShaderSemanticColorTest} 与
 * {@code ChainPreviewShaderFenceTest}（经原包校验器间接使用注释剥离）、
 * {@code chain.client.verify.T48cCRequiredUniformReachabilityTest}（声明面；其正则口径的替换见该类注释）。</p>
 */
public final class GlslSourceScanner {

    private final Map<String, String> attributes = new LinkedHashMap<String, String>();
    private final Map<String, String> varyings = new LinkedHashMap<String, String>();
    private final Map<String, String> uniforms = new LinkedHashMap<String, String>();
    private final Map<String, String> functions = new LinkedHashMap<String, String>();
    private final Map<String, String> functionBodies = new LinkedHashMap<String, String>();

    private GlslSourceScanner() {}

    /**
     * 剥掉 GLSL 注释（{@code //} 与 {@code /* … *}{@code /}），换行原样保留以便逐行对照。
     *
     * <p>与 {@link JavaSourceSlices#stripComments(String)} 的差别：GLSL 没有字符串字面量，
     * 因此不做字面量保护；行注释与块注释内的换行都保留（块注释外的换行计数不受影响）。</p>
     */
    public static String stripComments(String source) {
        return stripComments(source, new ArrayList<Integer>());
    }

    /**
     * 同上，并把未闭合块注释的起始行号（1 起）追加进 {@code unterminatedBlockCommentLines}。
     *
     * <p>供 GLSL 校验器把「块注释未闭合」记成一条 error：本方法是注释剥离的唯一真源，
     * 校验器只负责把行号翻译成它自己的发现条目。</p>
     */
    public static String stripComments(String source, List<Integer> unterminatedBlockCommentLines) {
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
                    unterminatedBlockCommentLines.add(Integer.valueOf(startLine));
                }
                continue;
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    /** 读取一份 GLSL 源码并抽取顶层结构（内部先 {@link #stripComments(String)}，注释里的名字不参与匹配）。 */
    public static GlslSourceScanner of(String source) {
        GlslSourceScanner scanner = new GlslSourceScanner();
        String code = stripComments(source);
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.startsWith("attribute ")) {
                putDeclaration(scanner.attributes, trimmed, "attribute ");
            } else if (trimmed.startsWith("varying ")) {
                putDeclaration(scanner.varyings, trimmed, "varying ");
            } else if (trimmed.startsWith("uniform ")) {
                putDeclaration(scanner.uniforms, trimmed, "uniform ");
            } else if (isFunctionHead(trimmed)) {
                String name = functionName(trimmed);
                if (name != null) {
                    scanner.functions.put(name, trimmed);
                    scanner.functionBodies.put(name, extractBody(lines, i));
                }
            }
        }
        return scanner;
    }

    private static void putDeclaration(Map<String, String> target, String trimmed, String keyword) {
        String rest = trimmed.substring(keyword.length()).trim();
        int semicolon = rest.indexOf(';');
        if (semicolon > 0) {
            rest = rest.substring(0, semicolon).trim();
        }
        int space = rest.indexOf(' ');
        if (space <= 0) {
            return;
        }
        String type = rest.substring(0, space);
        String name = rest.substring(space + 1).trim();
        int arrayStart = name.indexOf('[');
        if (arrayStart > 0) {
            name = name.substring(0, arrayStart);
        }
        target.put(name, type);
    }

    private static boolean isFunctionHead(String trimmed) {
        // 函数定义可能写作 "void main(void) {" 或 "void main(void)"：统一去掉尾部花括号再判定。
        String head = trimmed.endsWith("{") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
        if (!head.endsWith(")") || head.indexOf('(') <= 0) {
            return false;
        }
        if (head.startsWith("if") || head.startsWith("for") || head.startsWith("while")
                || head.startsWith("else") || head.startsWith("return") || head.contains("=")) {
            return false;
        }
        return functionName(head) != null;
    }

    private static String functionName(String head) {
        int paren = head.indexOf('(');
        if (paren <= 0) {
            return null;
        }
        String prefix = head.substring(0, paren).trim();
        int space = prefix.lastIndexOf(' ');
        if (space <= 0) {
            return null;
        }
        String name = prefix.substring(space + 1).trim();
        return name.isEmpty() ? null : name;
    }

    private static String extractBody(String[] lines, int headLine) {
        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = headLine; i < lines.length; i++) {
            String line = lines[i];
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') {
                    depth++;
                    started = true;
                    continue;
                }
                if (ch == '}') {
                    depth--;
                }
            }
            if (started) {
                body.append(line).append('\n');
            }
            if (started && depth <= 0) {
                break;
            }
        }
        return body.toString();
    }

    /** 顶层 {@code attribute <type> <name>;} 的「名字 → 类型」表（保序）。 */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** 顶层 {@code varying <type> <name>;} 的「名字 → 类型」表（保序）。 */
    public Map<String, String> getVaryings() {
        return varyings;
    }

    /** 顶层 {@code uniform <type> <name>;} 的「名字 → 类型」表（保序）。 */
    public Map<String, String> getUniforms() {
        return uniforms;
    }

    /** 全部函数名（保序）。 */
    public Set<String> getFunctionNames() {
        return new LinkedHashSet<String>(functions.keySet());
    }

    /** 取函数体（含签名行）；不存在时返回空串。 */
    public String body(String functionName) {
        String body = functionBodies.get(functionName);
        return body == null ? "" : body;
    }

    /** 统计函数体里出现的标识符次数（仅按词边界，用于确认某 uniform/属性是否被真的读取）。 */
    public static int countIdentifier(String text, String identifier) {
        int count = 0;
        int index = 0;
        while (index < text.length()) {
            int at = text.indexOf(identifier, index);
            if (at < 0) {
                break;
            }
            boolean leftOk = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
            int after = at + identifier.length();
            boolean rightOk = after >= text.length() || !Character.isJavaIdentifierPart(text.charAt(after));
            if (leftOk && rightOk) {
                count++;
            }
            index = at + identifier.length();
        }
        return count;
    }

    /** 收集源码中所有函数调用名（标识符后紧跟左括号）。 */
    public List<String> callsOf(String text) {
        List<String> calls = new ArrayList<String>();
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (!Character.isLetter(ch) && ch != '_') {
                index++;
                continue;
            }
            int start = index;
            while (index < text.length() && (Character.isLetterOrDigit(text.charAt(index)) || text.charAt(index) == '_')) {
                index++;
            }
            int probe = index;
            while (probe < text.length() && Character.isWhitespace(text.charAt(probe))) {
                probe++;
            }
            if (probe < text.length() && text.charAt(probe) == '(') {
                calls.add(text.substring(start, index));
            }
        }
        return calls;
    }
}
