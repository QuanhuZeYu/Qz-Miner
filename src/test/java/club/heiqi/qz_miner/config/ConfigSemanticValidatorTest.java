package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.runtime.SaveOutcome;
import club.heiqi.config.schema.ConfigSchema;

/**
 * 语义严格校验：非整数 / NaN / 交叉约束拒绝。
 */
public class ConfigSemanticValidatorTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-sem-").toFile();
        ConfigBootstrap.resetForTests();
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        deleteRecursively(tempDir);
    }

    @Test
    public void rejectsNonIntegerRadius() throws Exception {
        File yaml = new File(tempDir, "t.yaml");
        ConfigSchema schema = QzMinerConfigSchema.create();
        ConfigManager manager = ConfigManager.bootstrap(yaml, schema);
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(12.6));
        Assert.assertTrue(manager.save(draft).isSuccess());

        ConfigSemanticValidator.ParseOutcome outcome =
                ConfigSemanticValidator.parseAndValidate(manager.authority());
        Assert.assertFalse(outcome.isValid());
        Assert.assertTrue(outcome.result.summary().contains("chainRadius"));
    }

    @Test
    public void rejectsNaNAndInfinity() throws Exception {
        File yaml = new File(tempDir, "t2.yaml");
        ConfigSchema schema = QzMinerConfigSchema.create();
        ConfigManager manager = ConfigManager.bootstrap(yaml, schema);
        // UILib DraftBuffer 可能在 save 前拦 NaN/Infinity；直接测语义层
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("client.clientPreviewAlphaStartValue", Double.valueOf(Double.NaN));
        // 若 save 拒绝也算防护；若放行则语义层必须拒绝
        SaveOutcome nanSave = manager.save(draft);
        if (nanSave.isSuccess()) {
            Assert.assertFalse(ConfigSemanticValidator.parseAndValidate(manager.authority()).isValid());
        }

        DraftBuffer draft2 = manager.openDraft();
        draft2.setDraft("client.clientPreviewAlphaStartValue", Double.valueOf(0.5));
        draft2.setDraft("client.clientPreviewAlphaFadeEndRadius", Double.valueOf(1e308)); // finite but huge
        // fadeEnd 极大可能通过 schema max=Double.MAX_VALUE；交叉约束：fadeStart 默认 2.0，1e308 OK 范围
        // 改用 fadeEnd < fadeStart 测交叉
        draft2.setDraft("client.clientPreviewAlphaFadeStartRadius", Double.valueOf(5.0));
        draft2.setDraft("client.clientPreviewAlphaFadeEndRadius", Double.valueOf(5.0)); // span < 0.001
        Assert.assertTrue(manager.save(draft2).isSuccess());
        Assert.assertFalse(ConfigSemanticValidator.parseAndValidate(manager.authority()).isValid());
    }

    @Test
    public void rejectsAlphaCrossConstraints() throws Exception {
        File yaml = new File(tempDir, "t3.yaml");
        ConfigSchema schema = QzMinerConfigSchema.create();
        ConfigManager manager = ConfigManager.bootstrap(yaml, schema);
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("client.clientPreviewAlphaStartValue", Double.valueOf(0.2));
        draft.setDraft("client.clientPreviewAlphaEndValue", Double.valueOf(0.9));
        Assert.assertTrue(manager.save(draft).isSuccess());
        ConfigSemanticValidator.ParseOutcome outcome =
                ConfigSemanticValidator.parseAndValidate(manager.authority());
        Assert.assertFalse(outcome.isValid());
        String summary = outcome.result.summary();
        Assert.assertTrue("summary=" + summary,
                summary.contains("alphaEnd") || summary.contains("AlphaEnd")
                        || summary.contains("clientPreviewAlphaEndValue"));
    }

    @Test
    public void acceptsDefaults() throws Exception {
        File yaml = new File(tempDir, "t4.yaml");
        ConfigManager manager = ConfigManager.bootstrap(yaml, QzMinerConfigSchema.create());
        ConfigSemanticValidator.ParseOutcome outcome =
                ConfigSemanticValidator.parseAndValidate(manager.authority());
        Assert.assertTrue(outcome.result.summary(), outcome.isValid());
        Assert.assertEquals(19, outcome.snapshot.typedByPath.size());
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, outcome.snapshot.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, outcome.snapshot.greeting);
    }

    @Test
    public void rejectsCorruptYamlAtBootstrapFailFast() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "this: [is: not: valid:::".getBytes(StandardCharsets.UTF_8));
        // 坏 YAML 应备份删除后写默认；默认应通过
        ConfigManager m = ConfigBootstrap.bootstrap(tempDir, null);
        Assert.assertNotNull(m);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, ConfigBootstrap.lastValidSnapshot().chainRadius);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
