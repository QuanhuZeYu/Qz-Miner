package club.heiqi.qz_miner.config;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;
import club.heiqi.config.schema.SearchPickerSpec;
import club.heiqi.config.schema.ValueKind;
import club.heiqi.config.schema.ValueSpec;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;

/**
 * Schema 字段完备性与 Defaults 对齐。
 */
public class QzMinerConfigSchemaTest {

    @Test
    public void schemaContainsAllLegacyFieldsIncludingGreeting() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Assert.assertEquals("qz_miner", schema.modId());
        Assert.assertEquals(21, schema.allFields().size());
        Assert.assertTrue(schema.containsPath("client.autoToolSelection"));
        Assert.assertFalse(schema.containsPath("general.autoToolSelection"));
        Assert.assertTrue(schema.containsPath("general.greeting"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewAlphaEndValue"));
        Assert.assertTrue(schema.containsPath("client.objectGroups"));
    }

    @Test
    public void defaultsAlignWithQzMinerConfigDefaults() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        FieldSpec greeting = schema.field("general.greeting");
        Assert.assertEquals(FieldType.STRING, greeting.type());
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, greeting.defaultValue());

        FieldSpec radius = schema.field("general.chainRadius");
        Assert.assertEquals(FieldType.NUMBER, radius.type());
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CHAIN_RADIUS), radius.defaultValue());

        FieldSpec preview = schema.field("client.clientEnablePreviewRender");
        Assert.assertEquals(Boolean.valueOf(QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER),
                preview.defaultValue());

        FieldSpec alphaStart = schema.field("client.clientPreviewAlphaStartValue");
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE),
                alphaStart.defaultValue());

        FieldSpec groups = schema.field("client.objectGroups");
        Assert.assertEquals(FieldType.STRUCTURED_LIST, groups.type());
        Assert.assertEquals("已配置方块规则", groups.label());
        Assert.assertEquals("每组用组标识区分，选择适用模式，并配置组内包含的方块规则", groups.helper());
        Assert.assertEquals("id", groups.valueSpec().element().identityMember());
        Assert.assertEquals(ValueKind.LIST, groups.valueSpec().element().member("modes").spec().kind());
        Assert.assertEquals(ValueKind.CHOICE,
                groups.valueSpec().element().member("modes").spec().element().kind());
        Assert.assertArrayEquals(ObjectGroupMode.ids(), groups.valueSpec().element()
                .member("modes").spec().element().choices().toArray(new String[0]));
        club.heiqi.config.schema.ValueSpec members = groups.valueSpec().element().member("members").spec();
        Assert.assertEquals(ValueKind.LIST, members.kind());
        Assert.assertEquals(ValueKind.STRING, members.element().kind());
        Assert.assertTrue(members.widget() instanceof SearchPickerSpec);
        Assert.assertEquals("qz_miner:block-selector", ((SearchPickerSpec) members.widget()).editorId());
        Assert.assertEquals(64, ((SearchPickerSpec) members.widget()).maxItems());

        FieldSpec autoTool = schema.field("client.autoToolSelection");
        Assert.assertEquals(FieldType.STRUCTURED_LIST, autoTool.type());
        Assert.assertEquals("id", autoTool.valueSpec().element().identityMember());
        Assert.assertEquals(QzMinerConfigDefaults.autoToolSelection(), autoTool.defaultValue());
        assertAutoToolMembers(autoTool.valueSpec().element());
    }

    private static void assertAutoToolMembers(ValueSpec element) {
        Object[][] members = {
                { "id", ValueKind.CHOICE, "配置标识", "稳定标识", new String[] { "default" } },
                { "enabled", ValueKind.BOOLEAN, "启用", "启用自动工具", null },
                { "searchScope", ValueKind.CHOICE, "搜索范围", "搜索位置", new String[] { "inventory" } },
                { "restoreOriginal", ValueKind.BOOLEAN, "恢复原工具", "切回原工具", null },
                { "enchantmentPolicy", ValueKind.CHOICE, "附魔策略", "附魔偏好",
                        new String[] { "preserve_current" } },
                { "minimumRemainingDurability", ValueKind.NUMBER, "最低剩余耐久", "最低耐久", null },
                { "targetStableTicks", ValueKind.NUMBER, "目标稳定 Tick", "稳定多少 Tick", null },
                { "emptyTargetGraceTicks", ValueKind.NUMBER, "空目标宽限 Tick", "为空时等待", null }
        };
        for (Object[] expected : members) {
            assertMember(element, (String) expected[0], (ValueKind) expected[1], (String) expected[2],
                    (String) expected[3], (String[]) expected[4]);
        }
    }

    private static void assertMember(ValueSpec element, String name, ValueKind kind, String label,
            String helperMeaning, String[] choices) {
        Assert.assertEquals(name, element.member(name).name());
        Assert.assertEquals(kind, element.member(name).spec().kind());
        Assert.assertEquals(label, element.member(name).displayLabel());
        String helper = element.member(name).helper();
        Assert.assertNotNull(name + " helper", helper);
        Assert.assertFalse(name + " helper must not be blank", helper.trim().isEmpty());
        Assert.assertTrue(name + " helper must describe " + helperMeaning, helper.contains(helperMeaning));
        if (choices != null) {
            Assert.assertArrayEquals(choices,
                    element.member(name).spec().choices().toArray(new String[0]));
        }
    }
}
