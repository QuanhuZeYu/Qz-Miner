package club.heiqi.qz_miner.testsupport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.Assert;

/**
 * 语言文件（{@code assets/qz_miner/lang/*.lang}）的解析入口：把 {@code key=value} 变成可按 key 取值的表。
 *
 * <p><b>为什么存在</b>：本地化文案是<strong>被运行期按键读取</strong>的资源契约（键缺失或空文案
 * ⇒ 界面显示裸键名），而「某键在不在、文案空不空、中英是否各自本地化」必须按键判定——旧写法对整份
 * 文件做 {@code contains} 会被「键名多一截后缀」或「前缀恰好出现在别的键的值里」冒充放行。
 * 解析本身原先在 6 个用例类里各写一份（4 份手写行解析 + 2 份 JDK {@code Properties}），
 * 口径各异；已收敛到本类：解析只有一份，定位与「缺失即跳过 / 缺失即失败」的策略仍留在调用方。</p>
 *
 * <p><b>能证伪什么</b>：删掉某键（按 key 取值取不到）；把键改名成带后缀的邻居键；
 * 某键的文案被清空；中文里直接复制英文原文（两份解析结果相等）；语言文件被整体删掉
 * （定位型入口直接判失败，不会静默跳过）。判定对象是解析后的键值表，与行序、注释、缩进无关。</p>
 *
 * <p><b>守不到什么</b>：文案措辞与格式化占位符的运行期语义（{@code %s} 之类由消费方解释，
 * 本类只给字符串）；Minecraft 运行期真实的语言加载路径（{@code LanguageManager} / 资源包覆盖，
 * 测试读的是仓内源文件与构建产物）；{@code Properties} 口径的转义与注释规则（{@code \n} 会被
 * 反转义成换行、{@code !} 开头的行是注释、{@code =} 与 {@code :} 都是分隔符）——本仓 lang 文件
 * 全部是单行 {@code key=value} 且无转义（已实测），若将来引入转义写法，断言口径需回到这里改。</p>
 *
 * <p><b>服务哪些用例</b>：{@code chain.client.verify.BackendDiagnosticsConfigContractTest}、
 * {@code chain.client.verify.ExecutionProgressConfigContractTest}、
 * {@code client.configGUI.PreviewConfigTooltipsTest}、{@code client.QzMinerHudPresentationProjectionTest}、
 * {@code client.HudArchitectureBoundaryTest}、{@code client.configGUI.objectgroup.ObjectGroupEditorDensityHeadlessTest}。</p>
 */
public final class LanguageFiles {

    private LanguageFiles() {
    }

    /** 解析类路径语言资源（如 {@code assets/qz_miner/lang/en_US.lang}）；资源缺失即判失败。 */
    public static Properties read(String classpathResource) {
        InputStream stream = LanguageFiles.class.getClassLoader().getResourceAsStream(classpathResource);
        Assert.assertNotNull("语言资源必须存在: " + classpathResource, stream);
        return parse(stream, classpathResource);
    }

    /** 解析仓库相对路径的语言文件（工作目录逐级向上回溯）；文件缺失即判失败。 */
    public static Properties readRepoFile(String relativePath) {
        return load(new StringReader(JavaSourceSlices.readLocated(relativePath)), relativePath);
    }

    /**
     * 解析任意输入流（UTF-8）；流由本方法关闭。
     *
     * <p>供「自己定位文件、缺失时 Assume 跳过」的调用方使用（定位策略留在调用方，
     * 解析口径仍然只有这一份）。</p>
     */
    public static Properties parse(InputStream stream, String label) {
        try {
            return load(new InputStreamReader(stream, StandardCharsets.UTF_8), label);
        } finally {
            closeQuietly(stream);
        }
    }

    /** Map 视图（键 → 文案原文）；顺序不保证（{@code Properties} 是哈希表），判定只用键与值。 */
    public static Map<String, String> asMap(Properties language) {
        Map<String, String> entries = new LinkedHashMap<String, String>();
        for (String name : language.stringPropertyNames()) {
            entries.put(name, language.getProperty(name));
        }
        return entries;
    }

    private static Properties load(Reader reader, String label) {
        Properties language = new Properties();
        try {
            language.load(reader);
        } catch (IOException failure) {
            throw new AssertionError("lang 解析失败: " + label, failure);
        } finally {
            closeQuietly(reader);
        }
        return language;
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 只读资源，关闭失败无后续影响
        }
    }
}
