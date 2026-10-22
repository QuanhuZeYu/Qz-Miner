package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.uilib.ui.config.ConfigCodec;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;
import club.heiqi.uilib.ui.config.ConfigSchema;

/** 真实文档、真实文件系统；候选校验/编码/落盘失败都不得发布。 */
public class ConfigDocumentTransactionTest {
    private File directory;
    private final List<CommittedSnapshot> notifications = new ArrayList<CommittedSnapshot>();

    @Before
    public void setUp() throws Exception {
        ConfigTestSupport.reset();
        directory = Files.createTempDirectory("qz-document-transaction-").toFile();
        ConfigBootstrap.bootstrap(directory, null);
        ConfigBootstrap.subscribe(notifications::add);
    }

    @After
    public void tearDown() {
        ConfigTestSupport.reset();
        delete(directory);
    }

    @Test
    public void oldDoubleEncodedIntegerKeepsItsValueAcrossReadCommitAndReload() throws Exception {
        Files.write(ConfigBootstrap.yamlFile().toPath(),
                "general:\n  chainRadius: 32.0\n".getBytes(StandardCharsets.UTF_8));
        CommittedSnapshot loaded = ConfigBootstrap.reload();
        Assert.assertEquals(32, loaded.snapshot.chainRadius);
        Assert.assertEquals(Double.valueOf(32.0), ConfigBootstrap.document().get(QzMinerConfigSchema.CHAIN_RADIUS));
        ConfigDocument old = ConfigBootstrap.document();
        ConfigBootstrap.commit(ConfigTestSupport.changes("general.chainMaxBlocks", 4096.0));
        Assert.assertEquals(old.get(QzMinerConfigSchema.CHAIN_RADIUS),
                ConfigBootstrap.document().get(QzMinerConfigSchema.CHAIN_RADIUS));
        Assert.assertEquals(32, ConfigBootstrap.reload().snapshot.chainRadius);
    }

    @Test
    public void invalidScalarCandidatesHaveNoSideEffects() throws Exception {
        Object[][] cases = {
                {"general.chainRadius", 12.6}, {"general.chainRadius", "32"},
                {"general.tickBudgetMs", 0.0}, {"general.tickBudgetMs", 41.0},
                {"general.tickBudgetMs", 1.5}, {"client.clientPreviewAlphaStartValue", Double.NaN},
                {"client.tunnelDirectionSource", "camera_guess"},
                {"client.autoToolPrioritySelectors", java.util.Arrays.asList("ore:pick", " ore:pick ", "bad")}
        };
        for (Object[] input : cases) {
            ConfigDocument authority = ConfigBootstrap.document();
            CommittedSnapshot current = ConfigBootstrap.currentCommittedSnapshot();
            byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
            try {
                ConfigBootstrap.commit(ConfigTestSupport.changes(input));
                Assert.fail("必须拒绝非法候选 " + java.util.Arrays.toString(input));
            } catch (RuntimeException expected) {
                Assert.assertNotNull(expected.getMessage());
            }
            Assert.assertSame(authority, ConfigBootstrap.document());
            Assert.assertSame(current, ConfigBootstrap.currentCommittedSnapshot());
            Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
            Assert.assertTrue(notifications.isEmpty());
        }
    }

    @Test
    public void crossKeyCandidateIsValidatedTogetherAndPublishesExactlyOnce() throws Exception {
        ConfigDocument before = ConfigBootstrap.document();
        CommittedSnapshot committed = ConfigBootstrap.commit(ConfigTestSupport.changes(
                "client.clientPreviewAlphaFadeStartRadius", 10.0,
                "client.clientPreviewAlphaFadeEndRadius", 12.0,
                "general.chainRadius", 24.0));
        Assert.assertSame(committed, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(java.util.Collections.singletonList(committed), notifications);
        Assert.assertEquals(24, committed.snapshot.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, before.get(QzMinerConfigSchema.CHAIN_RADIUS), 0.0);
        // 提交通知只携带快照，运行字段必须由所属主线程派发。
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        ConfigDocument authority = ConfigBootstrap.document();
        byte[] disk = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());
        try {
            ConfigBootstrap.commit(ConfigTestSupport.changes("client.clientPreviewAlphaFadeEndRadius", 10.0));
            Assert.fail("必须拒绝跨键非法候选");
        } catch (RuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("fadeEnd"));
        }
        Assert.assertSame(authority, ConfigBootstrap.document());
        Assert.assertSame(committed, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertArrayEquals(disk, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
        Assert.assertEquals(1, notifications.size());
    }

    @Test
    public void failedAtomicReplacementPublishesNothingAndKeepsPreviousAuthority() throws Exception {
        ConfigDocument before = ConfigBootstrap.document();
        CommittedSnapshot committed = ConfigBootstrap.currentCommittedSnapshot();
        File yaml = ConfigBootstrap.yamlFile();
        File retained = new File(directory, "original.yaml");
        byte[] original = Files.readAllBytes(yaml.toPath());
        Files.move(yaml.toPath(), retained.toPath());
        Files.createDirectory(yaml.toPath());
        Files.write(new File(yaml, "occupied").toPath(), new byte[] {1});
        try {
            ConfigBootstrap.commit(ConfigTestSupport.changes("general.chainRadius", 24.0));
            Assert.fail("非空目录必须阻止原子替换");
        } catch (IOException expected) {
            Assert.assertNotNull(expected.getMessage());
        }
        Assert.assertSame(before, ConfigBootstrap.document());
        Assert.assertSame(committed, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertArrayEquals(original, Files.readAllBytes(retained.toPath()));
        Assert.assertTrue(notifications.isEmpty());
        for (File child : directory.listFiles()) Assert.assertFalse(child.getName().endsWith(".tmp"));
    }

    @Test
    public void encodingFailsBeforeTheTargetOrTemporaryFileIsTouched() throws Exception {
        File yaml = ConfigBootstrap.yamlFile();
        byte[] original = Files.readAllBytes(yaml.toPath());
        ConfigKey<String> key = new ConfigKey<String>("broken", new ConfigCodec<String>() {
            public String read(Object value, String path) { return (String) value; }
            public Object write(String value) { throw new IllegalStateException("encode failed"); }
            public String snapshot(String value) { return value; }
        }, "value");
        try {
            ConfigStorage.write(yaml.toPath(), ConfigDocument.defaults(ConfigSchema.of(key)));
            Assert.fail("编码失败必须在创建临时文件前退出");
        } catch (RuntimeException expected) {
            Assert.assertTrue(expected.getMessage().contains("encode failed"));
        }
        Assert.assertArrayEquals(original, Files.readAllBytes(yaml.toPath()));
        Assert.assertTrue(notifications.isEmpty());
        Assert.assertEquals(1, directory.listFiles().length);
    }

    @Test
    public void reloadRejectsAllMalformedInputBeforeReplacingAuthority() throws Exception {
        String[] inputs = {"general:\n  chainRadius: '42'\n", "client: null\n",
                "general:\n  chainRadius: 1.5\n", "general:\n  unknown: 1\n",
                "client:\n  clientPreviewAlphaStartValue: 0.1\n  clientPreviewAlphaEndValue: 0.9\n"};
        ConfigDocument before = ConfigBootstrap.document();
        CommittedSnapshot committed = ConfigBootstrap.currentCommittedSnapshot();
        for (String yaml : inputs) {
            Files.write(ConfigBootstrap.yamlFile().toPath(), yaml.getBytes(StandardCharsets.UTF_8));
            try {
                ConfigBootstrap.reload();
                Assert.fail("必须拒绝非法 reload: " + yaml);
            } catch (RuntimeException expected) {
                Assert.assertNotNull(expected.getMessage());
            }
            Assert.assertSame(before, ConfigBootstrap.document());
            Assert.assertSame(committed, ConfigBootstrap.currentCommittedSnapshot());
            Assert.assertTrue(notifications.isEmpty());
        }
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
