package club.heiqi.qz_miner.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.uilib.ui.config.ConfigCodecs;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;

/** 默认同源、键实例身份以及真实业务合法域。 */
public class QzMinerConfigSchemaTest {
    @Test
    public void schemaAndDefaultsShareTheSameRegisteredPathSetAndValues() {
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        QzMinerConfigDefaults.putAllDefaults(defaults);
        ConfigDocument document = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
        Set<String> paths = new LinkedHashSet<String>();
        for (ConfigKey<?> key : QzMinerConfigSchema.SCHEMA.keys()) {
            Assert.assertTrue(paths.add(key.name()));
            Assert.assertSame(key, QzMinerConfigSchema.key(key.name()));
            Assert.assertEquals(defaults.get(key.name()), document.get(key));
        }
        Assert.assertEquals(defaults.keySet(), paths);
        Assert.assertTrue(ConfigSemanticValidator.validate(document).isValid());
    }

    @Test
    public void sameNamedForeignKeyCannotMutateAuthority() {
        ConfigDocument document = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
        ConfigKey<Double> foreign = new ConfigKey<Double>("general.chainRadius", ConfigCodecs.DOUBLE, 8.0);
        try {
            document.withAll(java.util.Collections.singletonMap(foreign, 32.0));
            Assert.fail("键按实例身份识别，不能拼同名键绕过声明");
        } catch (IllegalArgumentException expected) {
            Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CHAIN_RADIUS), document.get(QzMinerConfigSchema.CHAIN_RADIUS));
        }
    }

    @Test
    public void harvestExhaustionKeepsNegativeZeroAndPositiveIssue242Domain() {
        ConfigDocument document = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
        Assert.assertEquals(Double.valueOf(0.025), document.get(QzMinerConfigSchema.HARVEST_EXHAUSTION_PER_BLOCK));
        for (double value : new double[] {-40.0, -0.025, 0.0, 0.025, 40.0}) {
            ConfigDocument candidate = document.withAll(java.util.Collections.singletonMap(
                    QzMinerConfigSchema.HARVEST_EXHAUSTION_PER_BLOCK, value));
            Assert.assertEquals(value, ConfigSemanticValidator.validate(candidate).snapshot.harvestExhaustionPerBlock, 0.0);
        }
    }
}
