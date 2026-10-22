package club.heiqi.qz_miner.config;

import java.io.StringReader;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigException;

/** 严格类型由实际 UILib 文档读取保证，Miner 不再重复实现 YAML 预检器。 */
public class RawYamlPreflightTest {
    @Test
    public void rejectsQuotedNumber() throws Exception {
        assertInvalid("general:\n  chainRadius: '12'\n", "general.chainRadius");
    }
    @Test
    public void rejectsQuotedBoolean() throws Exception {
        assertInvalid("general:\n  enableUnlimitedOreFortune: 'true'\n", "general.enableUnlimitedOreFortune");
    }
    @Test
    public void rejectsNumericStringField() throws Exception {
        assertInvalid("general:\n  greeting: 123\n", "general.greeting");
    }
    @Test
    public void rejectsExplicitNullButDistinguishesMissing() throws Exception {
        assertInvalid("general:\n  greeting: null\n", "general.greeting");
        ConfigDocument missing = read("general:\n  greeting: ok\n");
        Assert.assertEquals("ok", missing.get(QzMinerConfigSchema.GREETING));
        Assert.assertEquals(Double.valueOf(QzMinerConfigDefaults.CHAIN_RADIUS), missing.get(QzMinerConfigSchema.CHAIN_RADIUS));
    }
    @Test
    public void rejectsNonMapSection() throws Exception {
        assertInvalid("general: nope\n", "general");
    }
    @Test
    public void acceptsExactScalarTypesAndOldWholeDouble() throws Exception {
        ConfigDocument document = read("general:\n  greeting: hello\n  chainRadius: 12.0\n"
                + "  enableUnlimitedOreFortune: true\nclient:\n  clientEnablePreviewRender: false\n");
        Assert.assertEquals("hello", document.get(QzMinerConfigSchema.GREETING));
        Assert.assertEquals(Double.valueOf(12.0), document.get(QzMinerConfigSchema.CHAIN_RADIUS));
        Assert.assertEquals(Boolean.TRUE, document.get(QzMinerConfigSchema.ENABLE_UNLIMITED_ORE_FORTUNE));
        Assert.assertEquals(Boolean.FALSE, document.get(QzMinerConfigSchema.CLIENT_ENABLE_PREVIEW_RENDER));
    }
    @Test
    public void rejectsUnknownFieldsAndWrongListShape() throws Exception {
        assertInvalid("unknown:\n  future: 1\n", "unknown");
        assertInvalid("client:\n  autoToolPrioritySelectors: a\n", "client.autoToolPrioritySelectors");
        assertInvalid("client:\n  autoToolPrioritySelectors: [a, 1]\n", "client.autoToolPrioritySelectors[1]");
        assertInvalid("general.chainRadius: 12\n", "general.chainRadius");
    }
    private static ConfigDocument read(String yaml) throws Exception {
        return ConfigDocument.read(QzMinerConfigSchema.SCHEMA, new StringReader(yaml), "test.yaml");
    }
    private static void assertInvalid(String yaml, String path) throws Exception {
        try {
            read(yaml);
            Assert.fail("必须拒绝非法 YAML: " + yaml);
        } catch (ConfigException expected) {
            Assert.assertEquals(path, expected.path());
        }
    }
}
