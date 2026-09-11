package club.heiqi.qz_miner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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

    /** @return dependencies.gradle 实际引用的 qz_uilib jar 文件名（注册顺序、去重） */
    private static List<String> referencedQzUiLibJars() throws IOException {
        String gradle = readAll(Files.newInputStream(new File("dependencies.gradle").toPath()));
        LinkedHashSet<String> jars = new LinkedHashSet<String>();
        Matcher matcher = QZ_UILIB_JAR_REFERENCE.matcher(gradle);
        while (matcher.find()) {
            jars.add(matcher.group(1));
        }
        return new ArrayList<String>(jars);
    }

    /**
     * @return {@code @Mod} 依赖里 qz_uilib 的区间规格（如 {@code [4.10.0,5.0.0)}）
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
