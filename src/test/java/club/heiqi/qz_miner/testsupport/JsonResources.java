package club.heiqi.qz_miner.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Assert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 资源 JSON 的结构化读取入口（{@code mixins.*.json} 这类随 jar 发布的清单）。
 *
 * <p><b>为什么存在</b>：「某个 mixin 类是否登记在某张表里」是真实架构契约——登记错表
 * （client 表写进通用 {@code mixins}）会在服务端侧加载并崩溃，漏登记则静默不生效；
 * 但按 JSON 解析再判归属这件事原先在 3 个用例类里各写了一份内联副本（Gson 解析 + 抠数组 +
 * 简单名归一），口径迟早分叉。本类把「定位 → 解析 → 取数组 → 归一名」收敛成一份实现；
 * Gson 由 Forge 运行期提供，测试 JVM 已实测可用（与仓内既有用例同源）。</p>
 *
 * <p><b>能证伪什么</b>：删掉某张表或某个条目（归属断言立刻变红）；把条目从 client 表挪到
 * {@code mixins} / {@code server} 表；数组元素写成对象而非字符串（解析即失败，不给静默放行）；
 * JSON 语法损坏（解析失败带 label 抛出）。判定的是<strong>解析后的清单归属</strong>，
 * 与缩进、键序、注释、条目是否带子包前缀无关。</p>
 *
 * <p><b>守不到什么</b>：Mixin 运行期是否真的应用成功（那是 Mixin AP / 真机的职责，
 * 本类只看清单文本）；条目的拼写是否对应真实存在的类（字符串清单不认识类型）；
 * 非本模组的 JSON 资源语义；也不做 schema 校验——字段名写错会表现为「数组缺失」，
 * 调用方须用 {@link #hasArray} 把「清单必须声明该数组」单独钉住。</p>
 *
 * <p><b>服务哪些用例</b>：{@code mixins.AutoToolSwapMixinStructureTest}、
 * {@code mixins.ServerConfigurationManagerLifecycleMixinStructureTest}、
 * {@code chain.client.verify.HighlightWiringContractTest}。</p>
 */
public final class JsonResources {

    private JsonResources() {
    }

    /** 从类路径资源解析（UTF-8）；资源缺失或内容非法都判失败（构建产物缺失时必须吵）。 */
    public static JsonObject readResource(String resourcePath) {
        InputStream stream = JsonResources.class.getClassLoader().getResourceAsStream(resourcePath);
        Assert.assertNotNull("资源必须存在: " + resourcePath, stream);
        try {
            Reader reader = new InputStreamReader(stream, "UTF-8");
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
            return parse(text.toString(), resourcePath);
        } catch (IOException failure) {
            throw new AssertionError("读取资源失败: " + resourcePath, failure);
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
                // 只读资源，关闭失败无后续影响
            }
        }
    }

    /** 从仓库相对路径解析（工作目录逐级向上回溯，口径同 {@link JavaSourceSlices#readLocated(String)}）。 */
    public static JsonObject readRepoFile(String relativePath) {
        return parse(JavaSourceSlices.readLocated(relativePath), relativePath);
    }

    /** 解析 JSON 文本；{@code label} 用于失败消息（资源路径或清单名）。 */
    public static JsonObject parse(String json, String label) {
        try {
            return new JsonParser().parse(json).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new AssertionError("JSON 解析失败（必须是对象）: " + label, failure);
        }
    }

    /** 该键是否为数组（数组缺失即 false）。调用方据此把「清单必须声明该数组」显式钉住。 */
    public static boolean hasArray(JsonObject config, String arrayName) {
        return config.get(arrayName) instanceof JsonArray;
    }

    /** 字符串数组元素集合（保序去重）；数组缺失返回空集，元素非字符串判失败。 */
    public static Set<String> arrayEntrySet(JsonObject config, String arrayName) {
        Set<String> entries = new LinkedHashSet<String>();
        JsonArray array = arrayOf(config, arrayName);
        if (array == null) {
            return entries;
        }
        for (JsonElement entry : array) {
            entries.add(text(entry, arrayName));
        }
        return entries;
    }

    /**
     * 末段简单名集合（条目可带子包前缀，如 {@code client.X} 归一为 {@code X}）；
     * 数组缺失返回空集。归属判定的对象是「哪个类」，不是「写着哪种前缀」。
     */
    public static Set<String> simpleNameSet(JsonObject config, String arrayName) {
        Set<String> names = new LinkedHashSet<String>();
        for (String entry : arrayEntrySet(config, arrayName)) {
            int separator = entry.lastIndexOf('.');
            names.add(separator < 0 ? entry : entry.substring(separator + 1));
        }
        return names;
    }

    private static JsonArray arrayOf(JsonObject config, String arrayName) {
        JsonElement element = config.get(arrayName);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        Assert.assertTrue("mixin 清单的 " + arrayName + " 必须是数组: " + element, element instanceof JsonArray);
        return (JsonArray) element;
    }

    private static String text(JsonElement entry, String arrayName) {
        Assert.assertTrue("mixin 清单 " + arrayName + " 的条目必须是字符串: " + entry,
                entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString());
        return entry.getAsString();
    }
}
