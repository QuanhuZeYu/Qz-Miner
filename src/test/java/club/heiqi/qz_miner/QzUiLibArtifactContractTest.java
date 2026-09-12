package club.heiqi.qz_miner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.Assert;
import org.junit.Test;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.common.versioning.InvalidVersionSpecificationException;
import cpw.mods.fml.common.versioning.VersionRange;

/**
 * 制品契约守卫：{@code dependencies.gradle} 实际引用的 Qz-UILib dev jar，其 <b>jar 内部版本</b>
 * 必须满足 {@code MyMod} 声明的运行期区间。
 *
 * <p><b>为什么需要这条</b>（G1 缺口）：源码级守卫只能证明「{@code @Mod} 写了什么区间」，
 * 证明不了「交付的 jar 是什么版本」。R1 形态的旧制品（文件名 {@code qz_uilib-4.9.0-4-0.3+…-dirty-dev.jar}）
 * 在 822 用例全绿的情况下漏过，直到真机加载才报缺依赖——因为 jar 内 {@code mcmod.info} 的 version
 * 低于源码声明的下界，而没有任何测试读过它。</p>
 *
 * <p>判定用与运行期同源的 Forge 版本解析器 {@link VersionRange}（1.7.10 的包名是
 * {@code cpw.mods.fml.common.versioning}，即 1.8+ 的 {@code net.minecraftforge.fml.common.versioning}）；
 * 若测试 JVM 里该类不可用或区间规格无法解析，退化为显式语义比较（见 {@link #satisfiesRange} 注释）。</p>
 */
public class QzUiLibArtifactContractTest {

    /** dependencies.gradle 里引用的 qz_uilib jar（文件名捕获）。 */
    private static final Pattern QZ_UILIB_JAR_REFERENCE =
            Pattern.compile("libs/(qz_uilib-[^\"'()\\s,;]+\\.jar)");

    /** qz_uilib dev 制品文件名形态：{@code qz_uilib-<version>-dev.jar}。 */
    private static final Pattern JAR_FILE_NAME_VERSION =
            Pattern.compile("^qz_uilib-(.+)-dev\\.jar$");

    /** 显式语义比较的退化路径支持的区间形态：[下界,上界) / [下界,上界]。 */
    private static final Pattern SEMANTIC_RANGE =
            Pattern.compile("^\\[([^,\\]]+),([^,\\]]+)([)\\]])$");

    @Test
    public void referencedQzUiLibArtifactsSatisfyDeclaredRuntimeRange() throws Exception {
        List<String> referencedJars = referencedQzUiLibJars();
        Assert.assertFalse("dependencies.gradle 必须实际引用 libs/ 下的 qz_uilib jar",
                referencedJars.isEmpty());

        String declaredRange = declaredQzUiLibRange();
        for (String fileName : referencedJars) {
            File jar = new File("libs", fileName);
            Assert.assertTrue("依赖引用的 jar 必须存在（防引用与文件漂移）: " + jar.getPath(),
                    jar.isFile());

            String modVersion = readMcModInfoVersion(jar);
            Assert.assertFalse("mcmod.info 版本不得为空: " + jar.getName(), modVersion.isEmpty());
            Assert.assertTrue("libs/" + fileName + " 的 jar 内部版本 " + modVersion
                            + " 必须满足 @Mod 声明的运行期区间 " + declaredRange,
                    satisfiesRange(modVersion, declaredRange));

            Assert.assertEquals("jar 文件名版本必须与 mcmod.info 版本一致（R1 缺依赖形态）: "
                            + fileName, modVersion, jarFileNameVersion(fileName));
        }
    }

    /** @return dependencies.gradle 实际引用的 qz_uilib jar 文件名（注册顺序、去重；注释不参与） */
    private static List<String> referencedQzUiLibJars() throws IOException {
        return extractReferencedJars(readAll(Files.newInputStream(new File("dependencies.gradle").toPath())));
    }

    /**
     * 从 Gradle 源码文本里提取被引用的 qz_uilib jar 文件名（注册顺序、去重）。
     *
     * <p>拆成独立入口是为了让「文本 → 引用列表」这一步可被直接单测：文件系统状态不可控，
     * 但解析行为必须可判别（见 {@link #jarReferenceExtractionIgnoresComments}）。</p>
     */
    static List<String> extractReferencedJars(String gradleSource) {
        LinkedHashSet<String> jars = new LinkedHashSet<String>();
        Matcher matcher = QZ_UILIB_JAR_REFERENCE.matcher(stripComments(gradleSource));
        while (matcher.find()) {
            jars.add(matcher.group(1));
        }
        return new ArrayList<String>(jars);
    }

    /**
     * 文本解析守卫：注释里举例的 jar 文件名不得被当成真实依赖引用。
     *
     * <p>2026-09-12 实际事故：段注释中的回退示例（{@code libs/qz_uilib-4.9.1-dev.jar}）被整文正则捕获，
     * 让「依赖引用的 jar 必须存在」断言在一个与代码无关的文本上失败 —— 而该旧件早已从 {@code libs/} 移除。
     * 同类前科：{@code BlockPickerEventRegistrationContractTest} 的 javadoc 被当成注解。</p>
     */
    @Test
    public void jarReferenceExtractionIgnoresComments() {
        String source = "    // 回退方式：把文件名改回 libs/qz_uilib-9.9.9-dev.jar 即可\n"
                + "    // api(\"libs/qz_uilib-8.8.8-dev.jar\") 只是历史示例\n"
                + "    devOnlyNonPublishable(project.files(\"libs/qz_uilib-4.10.0-dev.jar\"))\n"
                + "    testImplementation(project.files('libs/qz_uilib-4.10.0-dev.jar'))\n"
                + "    /* 块注释里的 libs/qz_uilib-7.7.7-dev.jar 同样不算引用 */\n";

        List<String> extracted = extractReferencedJars(source);

        Assert.assertEquals("注释里的 jar 名不得进入引用列表，代码里的引用必须去重保留",
                Collections.singletonList("qz_uilib-4.10.0-dev.jar"), extracted);
    }

    /**
     * 去掉 Groovy/Gradle 源码里的注释，只留可执行文本。
     *
     * <p><b>为什么必须去注释</b>：{@code dependencies.gradle} 的段注释会举例写出历史/回退用的 jar 文件名
     * （如 {@code qz_uilib-4.9.1-dev.jar}）。整文扫正则时这些示例会被当成真实依赖引用，于是「注释文字」
     * 与「文件系统」产生伪耦合：删掉一个只存在于注释里的旧件就让全量 build 变红（2026-09-12 实际发生）。
     * 同类前科：{@code BlockPickerEventRegistrationContractTest} 的 javadoc 被当成注解（2026-09-12）。</p>
     *
     * <p>同时跳过字符串字面量，避免 {@code "https://…"} 里的双斜杠被误判为注释起点。</p>
     */
    private static String stripComments(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                index += 2;
                while (index + 1 < source.length()
                        && !(source.charAt(index) == '*' && source.charAt(index + 1) == '/')) {
                    index++;
                }
                index = Math.min(index + 2, source.length());
                continue;
            }
            if (current == '"' || current == '\'') {
                char quote = current;
                stripped.append(current);
                index++;
                while (index < source.length() && source.charAt(index) != quote) {
                    if (source.charAt(index) == '\\' && index + 1 < source.length()) {
                        stripped.append(source.charAt(index));
                        index++;
                    }
                    if (index < source.length()) {
                        stripped.append(source.charAt(index));
                        index++;
                    }
                }
                if (index < source.length()) {
                    stripped.append(source.charAt(index));
                    index++;
                }
                continue;
            }
            stripped.append(current);
            index++;
        }
        return stripped.toString();
    }

    /**
     * 自洽守卫：被引用的 UILib 制品，其 {@code @Mod} 声明的远端版本区间必须接受<b>该制品自己的版本</b>。
     *
     * <p><b>为什么需要</b>（2026-09-12 事故）：制品携带 {@code acceptableRemoteVersions=[4.9.0,4.10.0)}
     * 而自身版本是 4.10.0 时，FML 判定「mod 拒绝自身版本」——启动期报
     * {@code appears to reject its own version number (4.10.0)}，进入世界时集成服务器
     * {@code Rejecting connection CLIENT: [FMLMod:qz_uilib{4.10.0}]} 后卸载全部维度
     * （症状：无法进入世界，且不生成 crash-report）。本仓原有断言只校验「jar 版本满足 Miner 的依赖区间」，
     * 两条都绿仍会把事故漏到真机；本条补的是「<b>上游制品自身自洽</b>」。</p>
     *
     * <p>区间为空串时 FML 走精确版本相等检查，自身版本恒被接受，跳过。</p>
     */
    @Test
    public void referencedQzUiLibArtifactAcceptsItsOwnVersion() throws Exception {
        List<String> referencedJars = referencedQzUiLibJars();
        Assert.assertFalse("dependencies.gradle 必须实际引用 libs/ 下的 qz_uilib jar",
                referencedJars.isEmpty());

        String range = referencedQzUiLibRemoteRange();
        if (range.isEmpty()) {
            return;
        }
        for (String fileName : referencedJars) {
            File jar = new File("libs", fileName);
            Assert.assertTrue("依赖引用的 jar 必须存在: " + jar.getPath(), jar.isFile());
            String modVersion = readMcModInfoVersion(jar);
            Assert.assertTrue("libs/" + fileName + " 自己声明的远端版本区间 " + range
                            + " 必须接受该制品自身版本 " + modVersion
                            + "（否则 FML 判定 mod 拒绝自身版本：集成服务器握手拒绝、进世界立即卸载全部维度）",
                    satisfiesRange(modVersion, range));
        }
    }

    /**
     * @return 被引用 UILib 制品自身的 {@code acceptableRemoteVersions}。
     *
     * <p>用反射读制品里的声明（而非本仓常量），保证校验对象就是 classpath 上实际加载的那一件。</p>
     */
    private static String referencedQzUiLibRemoteRange() {
        try {
            Class<?> uilibMyMod = Class.forName("club.heiqi.uilib.MyMod");
            Mod declaration = uilibMyMod.getAnnotation(Mod.class);
            Assert.assertNotNull("被引用的 Qz-UILib 制品必须暴露带 @Mod 的 club.heiqi.uilib.MyMod", declaration);
            return declaration.acceptableRemoteVersions();
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("classpath 上必须能加载被引用的 club.heiqi.uilib.MyMod", missing);
        }
    }

    /**
     * @return {@code @Mod} 依赖里 qz_uilib 的区间规格（如 {@code [4.9.1,5.0.0)}）
     *
     * <p>区间两端括号可以是 {@code [}/{@code (} 与 {@code ]}/{@code )} 的任意 Maven 组合，
     * 故按 {@code qz_uilib@} 到子句分隔符 {@code ;} 切片，不做括号形态假设。</p>
     */
    private static String declaredQzUiLibRange() {
        Mod metadata = MyMod.class.getAnnotation(Mod.class);
        Assert.assertNotNull("MyMod must retain @Mod metadata", metadata);
        String dependencies = metadata.dependencies();
        int at = dependencies.indexOf("qz_uilib@");
        Assert.assertTrue("@Mod 依赖必须声明 qz_uilib 区间: " + dependencies, at >= 0);
        int start = at + "qz_uilib@".length();
        int end = dependencies.indexOf(';', start);
        Assert.assertTrue("qz_uilib 区间子句必须以 ; 结束: " + dependencies, end > start);
        return dependencies.substring(start, end);
    }

    /**
     * 版本是否落在区间内。
     *
     * <p><b>主路径</b>：Forge {@link VersionRange#createFromVersionSpec(String)} +
     * {@link VersionRange#containsVersion}——与运行期 FML 依赖校验同一实现，故不会出现
     * 「测试口径通过、真机口径失败」。<b>退化路径</b>：仅当该类在当前 JVM 不可用
     * （{@link LinkageError}）或区间规格无法解析时，才用显式语义比较（{@code [下界,上界)} 形态），
     * 保证守卫在精简测试环境里仍有判定能力而不是静默放行。</p>
     */
    private static boolean satisfiesRange(String version, String rangeSpec) {
        try {
            VersionRange range = VersionRange.createFromVersionSpec(rangeSpec);
            return range.containsVersion(new DefaultArtifactVersion(version));
        } catch (InvalidVersionSpecificationException | RuntimeException | LinkageError unavailable) {
            return satisfiesSemanticRange(version, rangeSpec);
        }
    }

    /** 退化路径：{@code [下界,上界)} / {@code [下界,上界]} 的显式数字段比较。 */
    private static boolean satisfiesSemanticRange(String version, String rangeSpec) {
        Matcher matcher = SEMANTIC_RANGE.matcher(rangeSpec);
        Assert.assertTrue("无法解析 UILib 区间规格: " + rangeSpec, matcher.matches());
        boolean lowerOk = compareNumericVersions(version, matcher.group(1)) >= 0;
        int upper = compareNumericVersions(version, matcher.group(2));
        boolean upperOk = "]".equals(matcher.group(3)) ? upper <= 0 : upper < 0;
        return lowerOk && upperOk;
    }

    /** 数字段逐段比较（退化路径专用；主路径由 Forge 解析器负责完整 Maven 区间语义）。 */
    private static int compareNumericVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            int a = index < leftParts.length ? leadingInt(leftParts[index]) : 0;
            int b = index < rightParts.length ? leadingInt(rightParts[index]) : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    private static int leadingInt(String token) {
        Matcher matcher = Pattern.compile("^\\d+").matcher(token);
        return matcher.find() ? Integer.parseInt(matcher.group()) : 0;
    }

    /** @return jar 内 {@code mcmod.info} 的 modList[0].version */
    private static String readMcModInfoVersion(File jar) throws IOException {
        ZipFile zip = new ZipFile(jar);
        try {
            ZipEntry entry = zip.getEntry("mcmod.info");
            Assert.assertNotNull("jar 必须包含 mcmod.info: " + jar.getPath(), entry);
            String json = readAll(zip.getInputStream(entry));
            return extractModListFirstVersion(json, jar);
        } finally {
            zip.close();
        }
    }

    /**
     * 极简 mcmod.info 解析：只认 {@code modListVersion 2} 的 {@code modList[0].version}
     * （不为此引入 JSON 依赖；结构不符合预期即失败关闭，不静默跳过）。
     */
    private static String extractModListFirstVersion(String json, File jar) {
        Assert.assertEquals("mcmod.info 必须是 Forge modListVersion 2: " + jar.getPath(),
                2, intValueAfter(json, "\"modListVersion\""));
        int modList = json.indexOf("\"modList\"");
        Assert.assertTrue("mcmod.info 必须含 modList: " + jar.getPath(), modList >= 0);
        int firstObject = json.indexOf('{', modList);
        Assert.assertTrue("mcmod.info modList 必须含对象元素: " + jar.getPath(), firstObject >= 0);
        int versionKey = json.indexOf("\"version\"", firstObject);
        Assert.assertTrue("mcmod.info modList[0] 必须含 version: " + jar.getPath(), versionKey >= 0);
        int colon = json.indexOf(':', versionKey);
        int openQuote = json.indexOf('"', colon + 1);
        int closeQuote = openQuote < 0 ? -1 : json.indexOf('"', openQuote + 1);
        Assert.assertTrue("mcmod.info version 必须是字符串: " + jar.getPath(),
                colon > versionKey && openQuote > colon && closeQuote > openQuote);
        return json.substring(openQuote + 1, closeQuote);
    }

    private static int intValueAfter(String json, String key) {
        int keyIndex = json.indexOf(key);
        Assert.assertTrue("mcmod.info 必须含 " + key, keyIndex >= 0);
        int index = json.indexOf(':', keyIndex) + 1;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        int start = index;
        while (index < json.length() && Character.isDigit(json.charAt(index))) {
            index++;
        }
        Assert.assertTrue("mcmod.info " + key + " 必须是整数", index > start);
        return Integer.parseInt(json.substring(start, index));
    }

    /** {@code qz_uilib-<version>-dev.jar} → {@code <version>}。 */
    private static String jarFileNameVersion(String fileName) {
        Matcher matcher = JAR_FILE_NAME_VERSION.matcher(fileName);
        Assert.assertTrue("qz_uilib 制品文件名必须形如 qz_uilib-<version>-dev.jar: " + fileName,
                matcher.matches());
        return matcher.group(1);
    }

    private static String readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }
}
