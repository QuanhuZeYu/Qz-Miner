package club.heiqi.qz_miner.testsupport;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Assert;

/**
 * 测试用 Java 源码「结构契约」切片工具：读源码 → 去注释 / 屏蔽字面量 → 切方法体、块、实参区间 →
 * 在切片内判定标识符的存在性、位置、先后与集合内容。
 *
 * <p><b>为什么存在</b>：白盒结构契约（调用顺序、委派边界、禁止清单、清单内容、数据流去向）在纯 JVM 里
 * 没有行为探针——它们握在 Minecraft 的玩家 / 世界实体上，构造不出来；此时唯一诚实的形态是「结构关系」：
 * 先按花括号与圆括号切出方法体或实参区间，再比标识符与位置。本类同时消灭「整段源码文本快照」：
 * 整段快照会因改格式、改局部变量名、等价重写而误报，也会因别处出现同样字符串而漏报；切片后只比标识符与位置，
 * 误报面收窄到「方法体里的这个调用 / 这个字段真的没了」。本类由三个并行批次的三份等价工具
 * （JavaSources / MainSourceSlices / JavaSourceScanner）收敛而来，是三者能力的并集；
 * 原 JavaSourceScanner 的实例状态收敛为「{@link #maskedMainSource(String)} 返回已屏蔽源码串」，
 * 其余 region 查询全部转为纯函数。</p>
 *
 * <p><b>能证伪什么</b>：被守的那次调用 / 那个分支 / 那个实参被删掉、被换到别的实现里、或顺序被调换；
 * 被禁的类型 / 调用 / 字段出现在不该出现的区域内；某清单里多出或少掉一个准入常量；
 * 捕获到的中间结果没被继续传下去。</p>
 *
 * <p><b>守不到什么</b>：调用的真实后果（那需要玩家 / 世界实体）；反射或字符串拼接出来的动态调用；
 * 本类不解析 Java 语法，只做「去注释、屏蔽字面量 + 花括号与圆括号配平 + 标识符 indexOf」，
 * 因此被截区域内的括号字面量、泛型与方法引用写法仍可能干扰配平——调用点应避开含括号字面量的表达式，
 * 也不得用本类做整段文本快照式断言。</p>
 *
 * <p><b>服务用例</b>：
 * <ul>
 *   <li>client 域：{@code AutoToolClientWiringStructureTest}、{@code ClientConnectionListenerTest}、
 *       {@code HudArchitectureBoundaryTest}、{@code KeyListenerMouseEventStructureTest}、
 *       {@code BlockPickerRegistryWatcherTest}、{@code ClientAutoToolSwapRuntimePacketDispatchTest}、
 *       {@code QzAutoToolSwapClientTransportStructureTest}、{@code ToolSwapMinecraftFacadeTest}</li>
 *   <li>chain.planner / chain.state 域：{@code AutoToolSwapRoundPropagationStructureTest}、
 *       {@code ChainHarvestRulesTest}、{@code ChainInteractPlannerSeedFreezeStructureTest}、
 *       {@code ChainPlanningConfirmedCountContractTest}、{@code ChainPlanningDiagnosticsTest}、
 *       {@code ChainPlanningEventBridgeTest}、{@code ChainPlanningInteractionSeedPropagationStructureTest}、
 *       {@code InteractionMatcherStructureTest}、{@code ModeExtensionMatcherDecoratorTest}、
 *       {@code PlanningToolCapabilitySnapshotTest}、{@code ChainStateServiceToolSwapLifecycleTest}</li>
 *   <li>chain.executor / chain.interaction 域：{@code BlockInteractActionExecutorStructureTest}、
 *       {@code InteractionInventorySupportStructureTest}、{@code LiquidSourceInteractActionExecutorStructureTest}、
 *       {@code ServerPlayerPoseTransactionStructureTest}、
 *       {@code TargetRevalidatingBlockInteractActionExecutorStructureTest}、{@code InteractionRayTraceTest}</li>
 * </ul>
 * </p>
 *
 * <p><b>形近方法刻意并存（差别即语义，未经裁定不得合并）</b>：
 * <ul>
 *   <li>{@link #methodBody(String, String, String)} 从签名起点切片且含签名；
 *       {@link #methodBodyWithoutSignature(String, String)} 按声明定位、只取花括号体（不含签名），且要求入参已屏蔽。</li>
 *   <li>{@link #callArguments(String, String, String)} 从签名起点找实参括号；
 *       {@link #callArgumentsFromPrefixEnd(String, String, String)} 从签名前缀末尾找实参括号；
 *       {@link #splitCallArguments(String, String)} 返回顶层实参清单，且调用缺失时给空清单而非断言。</li>
 *   <li>{@link #stripComments(String)} 识别并原样保留字符串 / 字符字面量；
 *       {@link #stripCommentsIgnoringStringLiterals(String)} 不识别字面量，行注释后还会多补一个换行。</li>
 *   <li>{@link #read(String)} 严格直读且失败抛受检异常；
 *       {@link #readLocated(String)} 带工作目录祖先回溯且失败走 {@code Assert.fail}。</li>
 *   <li>{@link #block(String, String, String)} 断言锚点存在、从锚点起点找花括号；
 *       {@link #blockAfter(String, String)} 锚点缺失返回空串、从锚点末尾找花括号。</li>
 *   <li>{@link #count(String, String)} 是裸计数；{@link #wordCount(String, String)} 是词边界计数。</li>
 * </ul>
 * </p>
 */
public final class JavaSourceSlices {

    private JavaSourceSlices() {
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 按仓根相对路径读取 UTF-8 源码（测试工作目录即仓根）；文件不存在直接判失败。
     *
     * <p>与 {@link #readLocated(String)} 的差别：本方法只认调用时工作目录下的这一个相对路径，
     * 不做祖先回溯，且把 {@link IOException} 原样上抛。</p>
     */
    public static String read(String relativePath) throws IOException {
        File file = new File(relativePath);
        Assert.assertTrue("源码文件必须存在（测试工作目录应为仓根）: " + file.getAbsolutePath(), file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 读取 src/main 下的源码（相对路径；带工作目录向上回溯兜底，兼容 IDE 与 gradle 两种 cwd）。
     *
     * <p>与 {@link #read(String)} 的差别：直读失败后逐级回溯祖先目录，仍找不到才 {@code Assert.fail}，
     * 因此没有受检异常，也不区分「路径写错」与「工作目录不同」。</p>
     */
    public static String readLocated(String relativePath) {
        try {
            Path direct = Paths.get(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            }
            Path dir = Paths.get("").toAbsolutePath();
            while (dir != null) {
                Path candidate = dir.resolve(relativePath);
                if (Files.isRegularFile(candidate)) {
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                }
                dir = dir.getParent();
            }
        } catch (Exception failure) {
            Assert.fail("读取生产源码失败: " + relativePath + " -> " + failure);
        }
        Assert.fail("找不到生产源码: " + relativePath);
        return "";
    }

    /** 读取并去注释：注释里的标识符不得让结构断言误命中。 */
    public static String stripped(String relativePath) {
        return stripComments(readLocated(relativePath));
    }

    /**
     * 读取仓根相对路径下的主源码，并屏蔽注释与字符串 / 字符字面量（{@code mask} 口径）。
     *
     * <p>与 {@link #read(String)} 的差别：注释与字面量被整体替换为空白而非删除（换行保留），
     * 所得文本可直接交给 {@link #methodBodyWithoutSignature(String, String)}、
     * {@link #wordIndexOf(String, String)} 一类按声明 / 词边界定位的查询——
     * 注释里的同名方法、字面量里的括号都不会参与匹配。</p>
     *
     * @return 已屏蔽的源码文本（只保留真正的代码 token）
     */
    public static String maskedMainSource(String relativePath) throws IOException {
        File file = new File(relativePath);
        Assert.assertTrue("主源码必须存在: " + file.getPath(), file.isFile());
        return mask(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 去注释 / 屏蔽

    /**
     * 去注释，保留字符串 / 字符字面量与换行结构。
     *
     * <p>只处理词法层面的注释，不改变标识符与字面量本身——因此「该标识符是否出现在某方法体内」
     * 这类判断不会被文档注释里的同名方法名污染，也不会被字面量里的注释起始符带偏。</p>
     *
     * <p>与 {@link #stripCommentsIgnoringStringLiterals(String)} 的差别：本方法识别字面量并原样保留，
     * 行注释只留下原始那一个换行；后者不识别字面量，行注释后会产生两个换行。</p>
     */
    public static String stripComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int index = 0;
        while (index < code.length()) {
            char ch = code.charAt(index);
            if (ch == '/' && index + 1 < code.length() && code.charAt(index + 1) == '/') {
                while (index < code.length() && code.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (ch == '/' && index + 1 < code.length() && code.charAt(index + 1) == '*') {
                index += 2;
                while (index < code.length()
                        && !(code.charAt(index) == '*' && index + 1 < code.length() && code.charAt(index + 1) == '/')) {
                    if (code.charAt(index) == '\n') {
                        out.append('\n');
                    }
                    index++;
                }
                index = Math.min(code.length(), index + 2);
                continue;
            }
            if (ch == '"' || ch == '\'') {
                char quote = ch;
                out.append(ch);
                index++;
                while (index < code.length()) {
                    char inner = code.charAt(index);
                    out.append(inner);
                    index++;
                    if (inner == '\\') {
                        if (index < code.length()) {
                            out.append(code.charAt(index));
                            index++;
                        }
                        continue;
                    }
                    if (inner == quote) {
                        break;
                    }
                }
                continue;
            }
            out.append(ch);
            index++;
        }
        return out.toString();
    }

    /**
     * 去行注释与块注释（保留换行），避免注释里的字样污染标识符判定。
     *
     * <p>与 {@link #stripComments(String)} 的差别：本方法<b>不</b>识别字符串 / 字符字面量，
     * 因此字面量里的行注释或块注释起始符会被当成真注释，吞掉本行后续代码；
     * 行注释分支先补一个换行、外层再把该换行补一次，故每条行注释后留下两个换行。
     * 两个口径刻意并存：调用点原本按哪个口径写的，就继续用哪个，合并会静默改变切片内容。</p>
     */
    public static String stripCommentsIgnoringStringLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean block = false;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (block) {
                if (ch == '*' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                    block = false;
                    index++;
                    continue;
                }
                if (ch == '\n') {
                    out.append(ch);
                }
                continue;
            }
            if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                if (index < source.length()) {
                    out.append('\n');
                }
                continue;
            }
            if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                block = true;
                index++;
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 方法体 / 块切片

    /**
     * 截取以 {@code signature} 开头的方法体（从签名起到配平的花括号结束，<b>含签名</b>）。
     *
     * @param code      完整源码（调用方自行决定是否先去注释）
     * @param signature 方法签名前缀，例如 {@code "public void onClientTick("}
     * @param label     断言信息用的用例说明
     */
    public static String methodBody(String code, String signature, String label) {
        return methodBody(code, signature, 0, label);
    }

    /**
     * 从 {@code fromIndex} 起查找签名并截取配平方法体（同名重载定位用得上）。
     *
     * <p>与 {@link #methodBodyWithoutSignature(String, String)} 的差别：本方法按<b>签名文本</b>定位，
     * 返回的切片从签名起点开始、<b>含签名</b>，因此调用点必须给出足够独特的签名前缀；
     * 后者按声明定位且只返回花括号体。</p>
     */
    public static String methodBody(String code, String signature, int fromIndex, String label) {
        int at = code.indexOf(signature, fromIndex);
        Assert.assertTrue("找不到 " + label + "（签名=" + signature + "）", at >= 0);
        return bracesFrom(code, at, label);
    }

    /** 读取 → 去注释 → 截方法体。 */
    public static String methodBodyOf(String relativePath, String signature, String label) {
        return methodBody(stripped(relativePath), signature, label);
    }

    /**
     * 取方法体（花括号内，<b>不含签名行</b>）：先按声明定位方法名，跳过配平参数表，再取其后第一个花括号块。
     * 方法不存在、参数表不闭合、或只有抽象声明（分号结尾）都直接判失败——调用点都假设它存在。
     *
     * <p>与 {@link #methodBody(String, String, String)} 的差别：本方法用「前导非空白字符是标识符字符、
     * 大于号或右方括号，且其后紧跟左圆括号」排除 {@code .foo(}、{@code !foo(} 这类调用，因此同名方法名
     * 不会被调用点抢先命中；返回的切片<b>不含签名</b>。它要求入参是已屏蔽的源码
     * （见 {@link #maskedMainSource(String)}），否则注释里的同名方法会先被命中。</p>
     *
     * @param maskedCode 已屏蔽注释与字面量的源码
     * @param methodName 方法名（不含参数表）
     */
    public static String methodBodyWithoutSignature(String maskedCode, String methodName) {
        int nameAt = declarationOf(maskedCode, methodName);
        Assert.assertTrue("未找到方法声明: " + methodName, nameAt >= 0);
        int parenOpen = maskedCode.indexOf('(', nameAt + methodName.length());
        int parenClose = matching(maskedCode, parenOpen, '(', ')');
        Assert.assertTrue("方法参数表不闭合: " + methodName, parenClose > parenOpen);
        int braceOpen = nextSignificant(maskedCode, parenClose + 1, '{', ';');
        Assert.assertTrue("方法没有方法体（抽象/接口声明）: " + methodName,
                braceOpen > 0 && maskedCode.charAt(braceOpen) == '{');
        return blockAt(maskedCode, braceOpen);
    }

    /**
     * 截取从 {@code anchor} 起第一个花括号块（含 anchor 文本），按花括号配平；锚点缺失直接判失败。
     *
     * <p>与 {@link #blockAfter(String, String)} 的差别：本方法断言锚点存在，并从<b>锚点起点</b>开始找花括号；
     * 后者锚点缺失返回空串，并从<b>锚点末尾</b>开始找花括号。</p>
     */
    public static String block(String source, String anchor, String label) {
        int at = source.indexOf(anchor);
        Assert.assertTrue("找不到结构锚点 " + label + "（anchor=" + anchor + "）", at >= 0);
        return bracesFrom(source, at, label);
    }

    /**
     * 从 {@code anchor} 之后的第一个花括号起返回与之配对的块（含花括号）；锚点缺失或后面没有块时返回空串。
     *
     * <p>与 {@link #block(String, String, String)} 的差别：本方法把「锚点不存在」当作可查询状态（空串）
     * 而不是失败，因此可用于「某结构不存在」的否定式契约；花括号从锚点末尾之后开始找，
     * 因此锚点自身含花括号时结果与 {@code block} 不同。</p>
     */
    public static String blockAfter(String region, String anchor) {
        int at = wordIndexOf(region, anchor);
        if (at < 0) {
            return "";
        }
        int braceOpen = region.indexOf('{', at + anchor.length());
        if (braceOpen < 0) {
            return "";
        }
        return blockAt(region, braceOpen);
    }

    // ------------------------------------------------------------------ 实参切片

    /**
     * 截取 {@code signature} 调用的实参文本（配平圆括号内，不含括号本身）。
     *
     * <p>实参括号从<b>签名起点</b>起找：签名本身含左圆括号时，取到的可能是签名内部的括号，
     * 这一点与 {@link #callArgumentsFromPrefixEnd(String, String, String)} 相反。</p>
     */
    public static String callArguments(String code, String signature, String label) {
        return callArguments(code, signature, 0, label);
    }

    /** 从 {@code fromIndex} 起查找调用并截取其配平实参区间。 */
    public static String callArguments(String code, String signature, int fromIndex, String label) {
        int at = code.indexOf(signature, fromIndex);
        Assert.assertTrue("找不到调用 " + label + "（签名=" + signature + "）", at >= 0);
        int open = code.indexOf('(', at);
        Assert.assertTrue(label + " 缺实参括号", open >= 0);
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char ch = code.charAt(index);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(open + 1, index);
                }
            }
        }
        Assert.fail(label + " 实参括号不平衡");
        return "";
    }

    /**
     * 截取 {@code callPrefix} 调用的实参文本（圆括号内，不含括号本身），实参括号从<b>前缀末尾</b>起找。
     *
     * <p>与 {@link #callArguments(String, String, String)} 的差别：本方法从
     * {@code at + callPrefix.length() - 1} 开始找左圆括号，因此前缀内部含左圆括号时
     * （例如 {@code "ClientHudService.getInstance().register("}）取的是前缀末尾那个括号，
     * 而 {@code callArguments} 会命中前缀内部更早的那个括号、把实参切空。
     * 两个口径刻意并存：改口径会静默改变实参切片内容。</p>
     */
    public static String callArgumentsFromPrefixEnd(String source, String callPrefix, String label) {
        int at = source.indexOf(callPrefix);
        Assert.assertTrue("找不到调用 " + label + "（call=" + callPrefix + "）", at >= 0);
        int open = source.indexOf('(', at + callPrefix.length() - 1);
        Assert.assertTrue("调用 " + label + " 缺实参括号", open >= 0);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(open + 1, index);
                }
            }
        }
        Assert.fail(label + " 实参括号不平衡");
        return "";
    }

    /**
     * 指定调用的顶层实参切片：按括号配对取实参表，再按顶层逗号切分；调用或括号缺失时返回空清单。
     *
     * <p>与 {@link #callArguments(String, String, String)} 的差别：本方法返回<b>逐条实参清单</b>
     * 而不是整段实参文本，且把「调用不存在」当作可查询状态（空清单）而不是失败，
     * 因此调用点可以自行断言清单内容；括号从调用名末尾之后开始找。</p>
     *
     * @param region   已切片的区域
     * @param callName 调用名，可含限定前缀（如 {@code InteractionRayTrace.trace}）
     */
    public static List<String> splitCallArguments(String region, String callName) {
        int at = wordIndexOf(region, callName);
        if (at < 0) {
            return new ArrayList<String>();
        }
        int parenOpen = region.indexOf('(', at + callName.length());
        if (parenOpen < 0) {
            return new ArrayList<String>();
        }
        int parenClose = matching(region, parenOpen, '(', ')');
        if (parenClose < 0) {
            return new ArrayList<String>();
        }
        String inside = region.substring(parenOpen + 1, parenClose);
        List<String> arguments = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inside.length(); i++) {
            char ch = inside.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                arguments.add(inside.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = inside.substring(start).trim();
        arguments.add(last);
        return arguments;
    }

    // ------------------------------------------------------------------ 存在性 / 位置 / 顺序

    /** 标识符必须出现在切片内，返回其位置；缺失即失败（位置契约的锚点存在性守卫）。 */
    public static int requireAt(String slice, String label, String identifier) {
        int at = slice.indexOf(identifier);
        Assert.assertTrue("切片内必须出现 " + identifier + "（" + label + "）", at >= 0);
        return at;
    }

    /** 断言区域内必须出现该标识符/字面量（区间由调用方先裁好）。 */
    public static void assertContains(String code, String token, String label) {
        Assert.assertTrue(label + "：区域内必须出现 " + token, code.contains(token));
    }

    /** 标识符不得出现在切片内（否定式守卫，仍以标识符为判定单位）。 */
    public static void requireAbsent(String slice, String label, String identifier) {
        Assert.assertFalse(label + "：切片内不得出现 " + identifier,
                slice.contains(identifier));
    }

    /** 断言区域内不得出现该标识符/字面量（边界清单，区间由调用方先裁好）。 */
    public static void assertAbsent(String code, String token, String label) {
        Assert.assertFalse(label + "：区域内不得出现 " + token, code.contains(token));
    }

    /**
     * 区域内标识符出现位置（<b>词边界</b>）；不存在返回 -1。
     *
     * <p>与 {@link #requireAt(String, String, String)} 的差别：本方法要求匹配处两端不是标识符字符，
     * 因此 {@code "catch"} 不会命中 {@code "catches"}；缺失是返回值 -1 而不是断言失败。</p>
     */
    public static int wordIndexOf(String region, String identifier) {
        return wordIndexOf(region, identifier, 0);
    }

    /** 区域内从 {@code fromIndex} 起标识符出现位置（词边界）；不存在返回 -1。 */
    public static int wordIndexOf(String region, String identifier, int fromIndex) {
        int from = Math.max(fromIndex, 0);
        for (int at = region.indexOf(identifier, from); at >= 0; at = region.indexOf(identifier, at + 1)) {
            if (isWordAt(region, at, identifier)) {
                return at;
            }
        }
        return -1;
    }

    /** 区域内是否出现该标识符（词边界）。 */
    public static boolean mentions(String region, String identifier) {
        return wordIndexOf(region, identifier) >= 0;
    }

    /**
     * 断言 {@code earlier} 在 {@code code} 中先于 {@code later} 出现（两者都必须存在）。
     *
     * <p>由 {@code MainSourceSlices.assertBefore} 与 {@code JavaSources.requireOrder} 收敛而来：
     * 两者判定完全一致（两个锚点都必须存在 + 严格先后），只是形参顺序不同——本方法沿用
     * label 在末位的体例。注意这是存在性 + 顺序的复合守卫，不是单纯比较返回值。</p>
     */
    public static void assertBefore(String code, String earlier, String later, String label) {
        int earlierAt = code.indexOf(earlier);
        int laterAt = code.indexOf(later);
        Assert.assertTrue(label + "：区域内缺少锚点 " + earlier, earlierAt >= 0);
        Assert.assertTrue(label + "：区域内缺少锚点 " + later, laterAt >= 0);
        Assert.assertTrue(label + "：" + earlier + " 必须先于 " + later, earlierAt < laterAt);
    }

    /** 统计 token 在 code 中出现次数（调用点唯一性契约，裸子串计数）。 */
    public static int count(String code, String token) {
        int total = 0;
        int offset = 0;
        while ((offset = code.indexOf(token, offset)) >= 0) {
            total++;
            offset += token.length();
        }
        return total;
    }

    /**
     * 区域内标识符出现次数（<b>词边界</b>）。
     *
     * <p>与 {@link #count(String, String)} 的差别：本方法按词边界计数，因此
     * {@code "catch"} 不会把 {@code "catches"} 算进去；裸计数会。</p>
     */
    public static int wordCount(String region, String identifier) {
        int total = 0;
        int from = 0;
        while (true) {
            int at = wordIndexOf(region, identifier, from);
            if (at < 0) {
                return total;
            }
            total++;
            from = at + identifier.length();
        }
    }

    /**
     * 收集某个限定前缀后紧跟的标识符集合（枚举常量清单 / 方法引用清单）。
     *
     * <p>用于「清单内容与边界集合」类契约：例如某方法体内出现过的
     * {@code PlayerInteractEvent.Action.X} 常量集合、{@code ChainHarvestRules::y} 方法引用集合。
     * 断言的是集合本身（准入边界），因此加空白、换行、等价重排都不会误报，
     * 而增删一个被接受的常量会真红。</p>
     */
    public static Set<String> identifiersAfter(String code, String prefix) {
        Set<String> found = new LinkedHashSet<String>();
        int at = code.indexOf(prefix);
        while (at >= 0) {
            int begin = at + prefix.length();
            int end = begin;
            while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) {
                end++;
            }
            if (end > begin) {
                found.add(code.substring(begin, end));
            }
            at = code.indexOf(prefix, end > begin ? end : begin);
        }
        return found;
    }

    /**
     * 取包含 assignmentIndex 的赋值语句左侧目标标识符。
     *
     * <p>用于「捕获结果必须作为实参传下去」这类数据流断言：先定位捕获调用，再取出被赋值变量名，
     * 最后断言该变量名出现在目标调用的实参区间里。名称无关——改局部变量名不会误报，
     * 而「捕获了却不传」（改传 null/0）会真红。</p>
     */
    public static String assignmentTarget(String code, int assignmentIndex, String label) {
        Assert.assertTrue(label + "：缺少捕获调用锚点", assignmentIndex >= 0);
        int statementStart = Math.max(code.lastIndexOf(';', assignmentIndex),
                code.lastIndexOf('{', assignmentIndex)) + 1;
        int equals = code.indexOf('=', statementStart);
        Assert.assertTrue(label + "：捕获调用不在赋值语句内", equals > statementStart && equals < assignmentIndex);
        int end = equals;
        while (end > statementStart && Character.isWhitespace(code.charAt(end - 1))) {
            end--;
        }
        int begin = end;
        while (begin > statementStart && Character.isJavaIdentifierPart(code.charAt(begin - 1))) {
            begin--;
        }
        String target = code.substring(begin, end);
        Assert.assertTrue(label + "：无法解析赋值目标标识符", target.length() > 0);
        return target;
    }

    /** 区域内 {@code qualifier.<字段> =} 形式的赋值目标字段名（去重、保序）。 */
    public static List<String> assignedFields(String region, String qualifier) {
        Set<String> fields = new LinkedHashSet<String>();
        int from = 0;
        while (true) {
            int at = wordIndexOf(region, qualifier, from);
            if (at < 0) {
                return new ArrayList<String>(fields);
            }
            from = at + qualifier.length();
            int dot = skipSpaces(region, from);
            if (dot >= region.length() || region.charAt(dot) != '.') {
                continue;
            }
            int nameStart = skipSpaces(region, dot + 1);
            if (nameStart >= region.length() || !Character.isJavaIdentifierStart(region.charAt(nameStart))) {
                continue;
            }
            int nameEnd = nameStart;
            while (nameEnd < region.length() && Character.isJavaIdentifierPart(region.charAt(nameEnd))) {
                nameEnd++;
            }
            int assign = skipSpaces(region, nameEnd);
            if (assign < region.length() && region.charAt(assign) == '='
                    && (assign + 1 >= region.length() || region.charAt(assign + 1) != '=')) {
                fields.add(region.substring(nameStart, nameEnd));
            }
        }
    }

    /**
     * 区域内 {@code qualifier.<字段> = 值} 赋值的右值文本（不含分号）；没有该赋值时返回空串。
     * 用于「捕获必须取自对应来源」这类数据流契约。
     */
    public static String assignedValue(String region, String qualifier, String fieldName) {
        int from = 0;
        while (true) {
            int at = wordIndexOf(region, qualifier, from);
            if (at < 0) {
                return "";
            }
            from = at + qualifier.length();
            int dot = skipSpaces(region, from);
            if (dot >= region.length() || region.charAt(dot) != '.') {
                continue;
            }
            int nameStart = skipSpaces(region, dot + 1);
            if (nameStart >= region.length() || !Character.isJavaIdentifierStart(region.charAt(nameStart))) {
                continue;
            }
            int nameEnd = nameStart;
            while (nameEnd < region.length() && Character.isJavaIdentifierPart(region.charAt(nameEnd))) {
                nameEnd++;
            }
            if (!fieldName.equals(region.substring(nameStart, nameEnd))) {
                continue;
            }
            int assign = skipSpaces(region, nameEnd);
            if (assign >= region.length() || region.charAt(assign) != '='
                    || (assign + 1 < region.length() && region.charAt(assign + 1) == '=')) {
                continue;
            }
            int valueStart = skipSpaces(region, assign + 1);
            int valueEnd = valueStart;
            int depth = 0;
            while (valueEnd < region.length()) {
                char ch = region.charAt(valueEnd);
                if (ch == '(' || ch == '[' || ch == '{') {
                    depth++;
                } else if (ch == ')' || ch == ']' || ch == '}') {
                    depth--;
                } else if (ch == ';' && depth == 0) {
                    break;
                }
                valueEnd++;
            }
            return region.substring(valueStart, valueEnd).trim();
        }
    }

    // ------------------------------------------------------------------ 内部实现

    /** 从 at 起第一个花括号到其配平闭括号（含两端）。at 处及之前的花括号不会被当作块起点。 */
    private static String bracesFrom(String code, int at, String label) {
        int open = code.indexOf('{', at);
        Assert.assertTrue(label + " 缺花括号体", open > at);
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char ch = code.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(at, index + 1);
                }
            }
        }
        Assert.fail(label + " 花括号不平衡");
        return "";
    }

    private static int skipSpaces(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** 找方法声明：名字必须由返回类型/修饰符打头（排除 {@code .foo(}、{@code !foo(} 这类调用）。 */
    private static int declarationOf(String code, String methodName) {
        int from = 0;
        while (true) {
            int at = wordIndexOf(code, methodName, from);
            if (at < 0) {
                return -1;
            }
            from = at + methodName.length();
            int before = at - 1;
            while (before >= 0 && Character.isWhitespace(code.charAt(before))) {
                before--;
            }
            if (before < 0) {
                continue;
            }
            char prev = code.charAt(before);
            if (Character.isJavaIdentifierPart(prev) || prev == '>' || prev == ']') {
                int probe = skipSpaces(code, at + methodName.length());
                if (probe < code.length() && code.charAt(probe) == '(') {
                    return at;
                }
            }
        }
    }

    /** 词边界判定：只在 token 的对应端是标识符字符时才检查边界，token 本身可含圆括号、点号等符号。 */
    private static boolean isWordAt(String text, int at, String token) {
        if (Character.isJavaIdentifierPart(token.charAt(0)) && at > 0
                && Character.isJavaIdentifierPart(text.charAt(at - 1))) {
            return false;
        }
        int end = at + token.length();
        return !Character.isJavaIdentifierPart(token.charAt(token.length() - 1))
                || end >= text.length()
                || !Character.isJavaIdentifierPart(text.charAt(end));
    }

    /** 从 open 处的开括号出发找配对闭括号；找不到返回 -1。 */
    private static int matching(String text, int open, char openChar, char closeChar) {
        if (open < 0 || open >= text.length() || text.charAt(open) != openChar) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == openChar) {
                depth++;
            } else if (ch == closeChar) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 从 from 起第一个出现的 target 或 stopOn 的位置（用于区分方法体与抽象声明）。 */
    private static int nextSignificant(String text, int from, char target, char stopOn) {
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == target || ch == stopOn) {
                return i;
            }
        }
        return -1;
    }

    private static String blockAt(String text, int braceOpen) {
        int braceClose = matching(text, braceOpen, '{', '}');
        if (braceClose < 0) {
            return "";
        }
        return text.substring(braceOpen, braceClose + 1);
    }

    /** 去注释并屏蔽字符串/字符字面量：注释与字面量里的词不再参与匹配（换行保留，便于逐行对照）。 */
    private static String mask(String text) {
        StringBuilder masked = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    masked.append(' ');
                    i++;
                }
                continue;
            }
            if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                while (i < text.length()) {
                    boolean closing = text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/';
                    masked.append(text.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                    if (closing) {
                        masked.append(' ');
                        i++;
                        break;
                    }
                }
                continue;
            }
            if (ch == '"') {
                masked.append(' ');
                i++;
                while (i < text.length() && text.charAt(i) != '"') {
                    if (text.charAt(i) == '\\' && i + 1 < text.length()) {
                        masked.append(' ');
                        i++;
                    }
                    masked.append(text.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < text.length()) {
                    masked.append(' ');
                    i++;
                }
                continue;
            }
            if (ch == '\'') {
                int literalEnd = charLiteralEnd(text, i);
                if (literalEnd > 0) {
                    for (int k = i; k <= literalEnd; k++) {
                        masked.append(' ');
                    }
                    i = literalEnd + 1;
                    continue;
                }
            }
            masked.append(ch);
            i++;
        }
        return masked.toString();
    }

    /** 识别字符字面量的闭引号位置；不是字面量（如撇号）时返回 -1。 */
    private static int charLiteralEnd(String text, int start) {
        int i = start + 1;
        while (i < text.length() && i <= start + 3) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                return -1;
            }
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '\'') {
                return i;
            }
            i++;
        }
        return -1;
    }
}
