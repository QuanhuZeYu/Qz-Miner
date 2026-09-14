package club.heiqi.qz_miner.client.configGUI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.field.FieldRenderer;
import club.heiqi.config.ui.field.FieldRendererRegistry;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;

/**
 * 新增配置键 tooltip 本地化的行为契约（纯 JVM）：语言键命名、只替换 helper、缺失回退、
 * 只覆盖本批路径、语言文件键集合中英对称且覆盖全部新键。
 */
public class PreviewConfigTooltipsTest {

    private static final String LANG_ROOT = "assets/qz_miner/lang/";

    /** 语言键 = config.qz_miner.<path 末段>.tooltip。 */
    @Test
    public void tooltipKeyUsesLastSchemaPathSegment() {
        Assert.assertEquals("config.qz_miner.clientPreviewBarThickness.tooltip",
                PreviewConfigTooltips.tooltipKey("client.clientPreviewBarThickness"));
        Assert.assertEquals("config.qz_miner.parallelBudgetMode.tooltip",
                PreviewConfigTooltips.tooltipKey("general.parallelBudgetMode"));
    }

    /** 命中语言键时只替换 helper，其余字段原样；缺失或空值零分配回退。 */
    @Test
    public void localizedReplacesHelperOnlyAndFallsBackWhenTextMissing() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        FieldSpec spec = schema.field("client.clientPreviewBarThickness");
        final String key = PreviewConfigTooltips.tooltipKey(spec.path());

        FieldSpec localized = PreviewConfigTooltips.localized(spec, new PreviewConfigTooltips.TextResolver() {
            @Override
            public String resolve(String queried) {
                return key.equals(queried) ? "本地化 tooltip" : queried;
            }
        });
        Assert.assertEquals("本地化 tooltip", localized.helper());
        Assert.assertEquals(spec.label(), localized.label());
        Assert.assertEquals(spec.path(), localized.path());
        Assert.assertEquals(spec.type(), localized.type());
        Assert.assertEquals(spec.defaultValue(), localized.defaultValue());

        // 原版 StatCollector 缺失语言键时返回 key 本身 => 回退 Schema helper
        Assert.assertSame(spec, PreviewConfigTooltips.localized(spec,
                new PreviewConfigTooltips.TextResolver() {
                    @Override
                    public String resolve(String queried) {
                        return queried;
                    }
                }));
        // 空文本同样回退
        Assert.assertSame(spec, PreviewConfigTooltips.localized(spec,
                new PreviewConfigTooltips.TextResolver() {
                    @Override
                    public String resolve(String queried) {
                        return "";
                    }
                }));
    }

    /** 只覆盖 {@link PreviewConfigTooltips#paths()} 列出的路径：新键共享同一代理渲染器，旧键仍走类型默认渲染器。 */
    @Test
    public void installOverridesExactlyTheNewSchemaPaths() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        FieldRendererRegistry registry = FieldRendererRegistry.defaultRegistry();
        PreviewConfigTooltips.install(registry, new PreviewConfigTooltips.TextResolver() {
            @Override
            public String resolve(String key) {
                return key;
            }
        });
        String[] paths = PreviewConfigTooltips.paths();
        Assert.assertEquals(28, paths.length);
        for (String path : paths) {
            Assert.assertNotNull("schema 缺少新键 " + path, schema.field(path));
        }
        FieldRenderer first = registry.resolve(schema.field(paths[0]));
        Assert.assertNotNull(first);
        Assert.assertSame(first, registry.resolve(schema.field(paths[1])));
        Assert.assertNotSame(first, registry.resolve(schema.field("client.clientPreviewMaxRadius")));
        Assert.assertNotSame(first, registry.resolve(schema.field("general.greeting")));
    }

    /** 中英语言文件键集合完全一致，且每个新键都有非空 tooltip。 */
    @Test
    public void langFilesCarrySymmetricTooltipsForEveryNewKey() throws Exception {
        Map<String, String> zh = loadLang("zh_CN.lang");
        Map<String, String> en = loadLang("en_US.lang");
        Assert.assertEquals("语言文件键集合必须中英对称",
                new TreeSet<String>(zh.keySet()), new TreeSet<String>(en.keySet()));
        for (String path : PreviewConfigTooltips.paths()) {
            String key = PreviewConfigTooltips.tooltipKey(path);
            Assert.assertTrue("zh 缺少 " + key, zh.containsKey(key));
            Assert.assertTrue("en 缺少 " + key, en.containsKey(key));
            Assert.assertFalse("zh 空值 " + key, zh.get(key).trim().isEmpty());
            Assert.assertFalse("en 空值 " + key, en.get(key).trim().isEmpty());
        }
    }

    private static Map<String, String> loadLang(String name) throws IOException {
        InputStream in = PreviewConfigTooltipsTest.class.getClassLoader()
                .getResourceAsStream(LANG_ROOT + name);
        Assert.assertNotNull("缺少语言文件 " + name, in);
        Map<String, String> values = new LinkedHashMap<String, String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                if (split <= 0) {
                    continue;
                }
                values.put(trimmed.substring(0, split).trim(), trimmed.substring(split + 1));
            }
        } finally {
            reader.close();
        }
        return values;
    }
}
