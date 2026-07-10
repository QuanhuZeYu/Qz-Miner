package club.heiqi.qz_miner.config;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.config.schema.FieldSpec;
import club.heiqi.config.schema.FieldType;

/**
 * Schema 字段完备性与默认值测试（纯 JVM）。
 */
public class QzMinerConfigSchemaTest {

    @Test
    public void schemaContainsAllLegacyFieldsIncludingGreeting() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        Assert.assertEquals("qz_miner", schema.modId());
        Assert.assertTrue(schema.containsPath("general.greeting"));
        Assert.assertTrue(schema.containsPath("general.chainRadius"));
        Assert.assertTrue(schema.containsPath("general.chainMaxBlocks"));
        Assert.assertTrue(schema.containsPath("general.chainLoggingShellLayers"));
        Assert.assertTrue(schema.containsPath("general.maxBreakPerTick"));
        Assert.assertTrue(schema.containsPath("general.cableReplaceMaxPerTick"));
        Assert.assertTrue(schema.containsPath("general.chainWatchdogTimeoutTicks"));
        Assert.assertTrue(schema.containsPath("general.parallelTickMinDurationMs"));
        Assert.assertTrue(schema.containsPath("general.parallelTickServerWorkBudgetUnits"));
        Assert.assertTrue(schema.containsPath("general.enableUnlimitedOreFortune"));
        Assert.assertTrue(schema.containsPath("general.enableFortuneForPlacedOre"));
        Assert.assertTrue(schema.containsPath("client.clientEnablePreviewRender"));
        Assert.assertTrue(schema.containsPath("client.parallelTickClientWorkBudgetUnits"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewMaxRadius"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewMaxTargets"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewAlphaFadeStartRadius"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewAlphaFadeEndRadius"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewAlphaStartValue"));
        Assert.assertTrue(schema.containsPath("client.clientPreviewAlphaEndValue"));
        Assert.assertEquals(19, schema.allFields().size());
    }

    @Test
    public void numberDefaultsAndGreetingType() {
        ConfigSchema schema = QzMinerConfigSchema.create();
        FieldSpec greeting = schema.field("general.greeting");
        Assert.assertEquals(FieldType.STRING, greeting.type());
        Assert.assertEquals("Hello World", greeting.defaultValue());

        FieldSpec radius = schema.field("general.chainRadius");
        Assert.assertEquals(FieldType.NUMBER, radius.type());
        Assert.assertEquals(Double.valueOf(8.0), radius.defaultValue());
    }
}
