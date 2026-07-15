package club.heiqi.qz_miner.config;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;
import club.heiqi.config.schema.SearchPickerSpec;
import club.heiqi.config.schema.ValueKind;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;

/**
 * Schema 字段完备性与 Defaults 对齐。
 */
public class QzMinerConfigSchemaTest {

    @Test
    public void schemaContainsAllLegacyFieldsIncludingGreeting() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Assert.assertEquals("qz_miner", schema.modId());
        Assert.assertEquals(22, schema.allFields().size());
        Assert.assertTrue(schema.containsPath("general.greeting"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewAlphaEndValue"));
        Assert.assertTrue(schema.containsPath("client.objectGroups"));
        Assert.assertTrue(schema.containsPath("client.autoToolSwapEnabled"));
        Assert.assertTrue(schema.containsPath("client.autoToolPrioritySelectors"));
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

        FieldSpec toolSwap = schema.field("client.autoToolSwapEnabled");
        Assert.assertEquals(Boolean.TRUE, toolSwap.defaultValue());
        FieldSpec selectors = schema.field("client.autoToolPrioritySelectors");
        Assert.assertEquals(FieldType.SIMPLE_LIST, selectors.type());
        Assert.assertEquals(java.util.Collections.emptyList(), selectors.defaultValue());

        FieldSpec alphaStart = schema.field("client.clientPreviewAlphaStartValue");
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE),
                alphaStart.defaultValue());

        FieldSpec groups = schema.field("client.objectGroups");
        Assert.assertEquals(FieldType.STRUCTURED_LIST, groups.type());
        Assert.assertEquals("对象组", groups.label());
        Assert.assertEquals("按组标识和适用模式组织连锁挖掘对象", groups.helper());
        Assert.assertFalse(groups.helper().contains("已配置方块规则"));
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
        Assert.assertEquals(SearchPickerSpec.BindingMode.LIST_MEMBERS,
                ((SearchPickerSpec) members.widget()).bindingMode());
    }
}
