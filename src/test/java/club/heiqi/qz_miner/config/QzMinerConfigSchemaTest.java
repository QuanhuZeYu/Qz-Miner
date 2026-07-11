package club.heiqi.qz_miner.config;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;

/**
 * Schema 字段完备性与 Defaults 对齐。
 */
public class QzMinerConfigSchemaTest {

    @Test
    public void schemaContainsAllLegacyFieldsIncludingGreeting() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Assert.assertEquals("qz_miner", schema.modId());
        Assert.assertEquals(20, schema.allFields().size());
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
        Assert.assertEquals("id", groups.valueSpec().element().identityMember());
    }
}
