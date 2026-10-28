package club.heiqi.qz_miner.testsupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 剥离注释后抽取 GLSL 顶层属性、varying、uniform、函数及调用关系，供既有结构验证复用。
 * 声明按本仓单行写法识别，预处理分支统一纳入；语义编译、优化结果及 GL 绑定由编译器和实机确认。
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
