package club.heiqi.qz_miner.chain.client.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简 GLSL 顶层结构扫描器：抽取 attribute/varying/uniform 名字、函数签名与函数体。
 *
 * <p>供测试做「行为契约」级断言：例如「顶点着色器必须声明 attribute 0/1/2 三个属性」
 * 「片元着色器的颜色必须来自 uniform 而非 aColor」。它只认顶层声明，不做类型推导——
 * 类型推导与参数个数核对由 {@link Glsl120StaticChecker} 负责。</p>
 */
final class GlslSourceScanner {

    private final Map<String, String> attributes = new LinkedHashMap<String, String>();
    private final Map<String, String> varyings = new LinkedHashMap<String, String>();
    private final Map<String, String> uniforms = new LinkedHashMap<String, String>();
    private final Map<String, String> functions = new LinkedHashMap<String, String>();
    private final Map<String, String> functionBodies = new LinkedHashMap<String, String>();

    private GlslSourceScanner() {}

    static GlslSourceScanner of(String source, List<Glsl120StaticChecker.Finding> findings, String fileName) {
        GlslSourceScanner scanner = new GlslSourceScanner();
        String code = Glsl120StaticChecker.stripComments(source, fileName, findings);
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

    Map<String, String> getAttributes() {
        return attributes;
    }

    Map<String, String> getVaryings() {
        return varyings;
    }

    Map<String, String> getUniforms() {
        return uniforms;
    }

    Set<String> getFunctionNames() {
        return new LinkedHashSet<String>(functions.keySet());
    }

    /** 取函数体（含签名行）；不存在时返回空串。 */
    String body(String functionName) {
        String body = functionBodies.get(functionName);
        return body == null ? "" : body;
    }

    /** 统计函数体里出现的标识符次数（仅按词边界，用于确认某 uniform/属性是否被真的读取）。 */
    static int countIdentifier(String text, String identifier) {
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
    List<String> callsOf(String text) {
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
