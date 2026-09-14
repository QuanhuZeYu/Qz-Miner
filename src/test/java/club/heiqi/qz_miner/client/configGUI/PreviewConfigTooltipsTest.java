package club.heiqi.qz_miner.client.configGUI;

import java.util.Map;
import java.util.TreeSet;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.ui.field.FieldRenderer;
import club.heiqi.config.ui.field.FieldRendererRegistry;
import club.heiqi.qz_miner.config.QzMinerConfigSchema;
import club.heiqi.qz_miner.testsupport.LanguageFiles;

/**
 * 新增配置键文本本地化的行为契约（纯 JVM）：语言键命名、helper / label 各自替换与缺失回退、
 * 只覆盖本批路径、语言文件键集合中英对称且覆盖全部新键。
 *
 * <p>label 通道的存在理由见 {@link PreviewConfigTooltips} 的类注释：UILib 4.10 的
 * {@code FieldSpec.label} 没有语言表通道，只能用本仓既有的代理渲染器替换。</p>
 */
public class PreviewConfigTooltipsTest {

    private static final String LANG_ROOT = "assets/qz_miner/lang/";

    /** 语言键 = config.qz_miner.<path 末段>.<后缀>。 */
    @Test
    public void languageKeysUseLastSchemaPathSegment() {
        Assert.assertEquals("config.qz_miner.clientPreviewBarThickness.tooltip",
                PreviewConfigTooltips.tooltipKey("client.clientPreviewBarThickness"));
        Assert.assertEquals("config.qz_miner.parallelBudgetMode.tooltip",
                PreviewConfigTooltips.tooltipKey("general.parallelBudgetMode"));
        Assert.assertEquals("config.qz_miner.clientPreviewColorChain.label",
                PreviewConfigTooltips.labelKey("client.clientPreviewColorChain"));
        Assert.assertEquals("config.qz_miner.parallelBudgetMode.label",
                PreviewConfigTooltips.labelKey("general.parallelBudgetMode"));
    }

    /** 命中语言键时替换 helper / label，其余字段原样；缺失或空值零分配回退。 */
    @Test
    public void localizedReplacesHelperAndLabelAndFallsBackWhenTextMissing() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        FieldSpec spec = schema.field("client.clientPreviewBarThickness");
        final String helperKey = PreviewConfigTooltips.tooltipKey(spec.path());
        final String labelKey = PreviewConfigTooltips.labelKey(spec.path());

        FieldSpec both = PreviewConfigTooltips.localized(spec, new PreviewConfigTooltips.TextResolver() {
            @Override
            public String resolve(String queried) {
                if (helperKey.equals(queried)) {
                    return "本地化 tooltip";
                }
                if (labelKey.equals(queried)) {
                    return "本地化 label";
                }
                return queried;
            }
        });
        Assert.assertEquals("本地化 tooltip", both.helper());
        Assert.assertEquals("本地化 label", both.label());
        Assert.assertEquals(spec.path(), both.path());
        Assert.assertEquals(spec.type(), both.type());
        Assert.assertEquals(spec.defaultValue(), both.defaultValue());

        // 只命中 label：helper 必须保持 Schema 原文
        FieldSpec labelOnly = PreviewConfigTooltips.localized(spec, new PreviewConfigTooltips.TextResolver() {
            @Override
            public String resolve(String queried) {
                return labelKey.equals(queried) ? "只有 label" : queried;
            }
        });
        Assert.assertEquals("只有 label", labelOnly.label());
        Assert.assertEquals(spec.helper(), labelOnly.helper());

        // 原版 StatCollector 缺失语言键时返回 key 本身 => 回退 Schema 原文
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
        // 33 = 上一轮的 30 个受覆盖键，去掉 1 个旧颜色键、加上 3 个大模式颜色键（CHAIN / AREA / INTERACT）、
        //      再加 1 个内部结构亮度系数（clientPreviewInteriorDim）
        Assert.assertEquals(33, paths.length);
        for (String path : paths) {
            Assert.assertNotNull("schema 缺少新键 " + path, schema.field(path));
        }
        FieldRenderer first = registry.resolve(schema.field(paths[0]));
        Assert.assertNotNull(first);
        Assert.assertSame(first, registry.resolve(schema.field(paths[1])));
        Assert.assertNotSame(first, registry.resolve(schema.field("client.clientPreviewMaxRadius")));
        Assert.assertNotSame(first, registry.resolve(schema.field("general.greeting")));
    }

    /** 中英语言文件键集合完全一致，且每个新键都有非空 tooltip、六个颜色键都有非空 label。 */
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
        for (String leaf : new String[] {"clientPreviewColorChain", "clientPreviewColorArea",
                "clientPreviewColorInteract", "clientPreviewColorSecondary",
                "clientPreviewColorRemote", "clientPreviewColorTruncated"}) {
            String key = PreviewConfigTooltips.labelKey("client." + leaf);
            Assert.assertTrue("zh 缺少 " + key, zh.containsKey(key));
            Assert.assertTrue("en 缺少 " + key, en.containsKey(key));
            Assert.assertFalse("zh 空值 " + key, zh.get(key).trim().isEmpty());
            Assert.assertFalse("en 空值 " + key, en.get(key).trim().isEmpty());
        }
    }

    private static Map<String, String> loadLang(String name) {
        return LanguageFiles.asMap(LanguageFiles.read(LANG_ROOT + name));
    }
}
