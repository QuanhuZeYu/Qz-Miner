package club.heiqi.qz_miner.config;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.config.Config;
import club.heiqi.config.ConfigFormat;
import club.heiqi.config.ConfigNode;
import club.heiqi.config.schema.ConfigSchema;

/** Raw YAML NodeType 预检。 */
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
        ConfigNode nullRoot = Config.parse("general:\n  greeting: null\n", ConfigFormat.YAML);
        Assert.assertTrue(nullRoot.asMap().containsKey("general"));
        Assert.assertTrue(nullRoot.get("general").asMap().containsKey("greeting"));
        Assert.assertEquals(ConfigNode.NodeType.NULL, nullRoot.get("general.greeting").getType());
        assertInvalid(nullRoot, QzMinerConfigSchema.create(), "general.greeting");

        RawYamlPreflight.Result missing = validate("general:\n  greeting: ok\n");
        Assert.assertTrue(missing.summary(), missing.isValid());
    }

    @Test
    public void rejectsNonMapSection() throws Exception {
        assertInvalid("general: nope\n", "general");
    }

    @Test
    public void acceptsMissingFieldsForSchemaDefaults() throws Exception {
        RawYamlPreflight.Result result = validate("general:\n  greeting: hello\n");
        Assert.assertTrue(result.summary(), result.isValid());
    }

    @Test
    public void acceptsExactScalarTypes() throws Exception {
        String yaml = "general:\n"
                + "  greeting: hello\n"
                + "  chainRadius: 12\n"
                + "  enableUnlimitedOreFortune: true\n"
                + "client:\n"
                + "  clientEnablePreviewRender: false\n";
        RawYamlPreflight.Result result = validate(yaml);
        Assert.assertTrue(result.summary(), result.isValid());
    }

    @Test
    public void acceptsUnknownFieldsAndChecksSimpleListGenerically() throws Exception {
        RawYamlPreflight.Result unknown = validate("unknown:\n  future: 1\n");
        Assert.assertTrue(unknown.summary(), unknown.isValid());

        ConfigSchema listSchema = ConfigSchema.builder("test")
                .section("general")
                    .simpleList("names").build()
                .endSection()
                .build();
        ConfigNode legal = Config.parse("general:\n  names: [a, b]\n", ConfigFormat.YAML);
        Assert.assertTrue(RawYamlPreflight.validate(legal, listSchema).isValid());
        ConfigNode illegal = Config.parse("general:\n  names: a\n", ConfigFormat.YAML);
        assertInvalid(illegal, listSchema, "general.names");
    }

    private static RawYamlPreflight.Result validate(String yaml) throws Exception {
        return RawYamlPreflight.validate(Config.parse(yaml, ConfigFormat.YAML), QzMinerConfigSchema.create());
    }

    private static void assertInvalid(String yaml, String path) throws Exception {
        assertInvalid(Config.parse(yaml, ConfigFormat.YAML), QzMinerConfigSchema.create(), path);
    }

    private static void assertInvalid(ConfigNode root, ConfigSchema schema, String path) {
        RawYamlPreflight.Result result = RawYamlPreflight.validate(root, schema);
        Assert.assertFalse(result.isValid());
        Assert.assertTrue("errors=" + result.errors(), result.errors().containsKey(path));
    }
}
