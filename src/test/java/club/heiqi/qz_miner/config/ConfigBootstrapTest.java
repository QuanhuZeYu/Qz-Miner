package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.uilib.ui.config.ConfigDocument;
import cpw.mods.fml.relauncher.FMLInjectionData;

/** 启动导入与失败事务使用真实 YAML、cfg 和文件共享锁，不向生产注入替代存储。 */
public class ConfigBootstrapTest {
    private File directory;
    private Field minecraftHome;
    private Object previousHome;

    @Before
    public void setUp() throws Exception {
        directory = Files.createTempDirectory("qz-bootstrap-").toFile();
        minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        previousHome = minecraftHome.get(null);
        minecraftHome.set(null, directory);
        ConfigTestSupport.reset();
    }
    @After
    public void tearDown() throws Exception {
        ConfigTestSupport.reset();
        minecraftHome.set(null, previousHome);
        delete(directory);
    }

    @Test
    public void firstRunPersistsDefaultsAndSamePathBootstrapIsIdempotent() throws Exception {
        ConfigDocument first = ConfigBootstrap.bootstrap(directory, null);
        Assert.assertSame(first, ConfigBootstrap.bootstrap(directory, null));
        Assert.assertSame(first, ConfigBootstrap.document());
        Assert.assertTrue(ConfigBootstrap.yamlFile().isFile());
        Assert.assertTrue(ConfigBootstrap.yamlFile().length() > 0L);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, Config.greeting);
        Assert.assertTrue(Config.clientEnablePreviewRender);
        Assert.assertTrue(Config.autoToolSwapEnabled);
        Assert.assertEquals(QzMinerConfigDefaults.objectGroups(), first.get(QzMinerConfigSchema.OBJECT_GROUPS));
        try {
            ConfigBootstrap.bootstrap(new File(directory, "another"), null);
            Assert.fail("不同目录不能重复启动配置权威");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("already initialized"));
        }
    }

    @Test
    public void existingWholeDoubleYamlAppliesRuntimeAndCanonicalSelectors() throws Exception {
        File yaml = yaml(directory);
        write(yaml, "general:\n  greeting: RuntimeYaml\n  chainRadius: 32.0\n  tickBudgetMs: 22.0\n"
                + "client:\n  clientEnablePreviewRender: false\n  tunnelDirectionSource: hit_face\n"
                + "  autoToolSwapEnabled: false\n  autoToolPrioritySelectors: [' ore:toolPickaxe ', 'mod:drill@4']\n"
                + "  clientPreviewMaxRadius: 8.0\n  clientPreviewAlphaStartValue: 0.6\n"
                + "  clientPreviewAlphaEndValue: 0.1\n");
        ConfigBootstrap.bootstrap(directory, null);
        Assert.assertEquals("RuntimeYaml", Config.greeting);
        Assert.assertEquals(32, Config.chainRadius);
        Assert.assertEquals(22, Config.tickBudgetMs);
        Assert.assertEquals(8, Config.clientPreviewMaxRadius);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertFalse(Config.autoToolSwapEnabled);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, Config.tunnelDirectionSource);
        Assert.assertEquals("ore:toolPickaxe", Config.autoToolPrioritySelectors.get(0).canonicalText());
        Assert.assertEquals("mod:drill@4", Config.autoToolPrioritySelectors.get(1).canonicalText());
        Assert.assertFalse(hasBackup(directory, "invalid"));
    }

    @Test
    public void legacyBudgetMigrationPreservesBusinessAndRemovesAllFourOldKeys() throws Exception {
        File yaml = yaml(directory);
        write(yaml, "general:\n  greeting: MigratedYaml\n  chainRadius: 31.0\n  maxBreakPerTick: 16\n"
                + "  parallelTickMinDurationMs: 12.0\n  parallelTickServerWorkBudgetUnits: 320\n"
                + "  enableUnlimitedOreFortune: true\nclient:\n  clientEnablePreviewRender: false\n"
                + "  tunnelDirectionSource: hit_face\n  parallelTickClientWorkBudgetUnits: 200\n"
                + "  clientPreviewAlphaStartValue: 0.78\n  clientPreviewAlphaEndValue: 0.15\n");
        byte[] original = Files.readAllBytes(yaml.toPath());
        ConfigBootstrap.bootstrap(directory, null);
        Assert.assertEquals("MigratedYaml", Config.greeting);
        Assert.assertEquals(31, Config.chainRadius);
        Assert.assertEquals(12, Config.tickBudgetMs);
        Assert.assertTrue(Config.enableUnlimitedOreFortune);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, Config.tunnelDirectionSource);
        Assert.assertEquals(0.78, Config.clientPreviewAlphaStartValue, 0.0);
        Assert.assertEquals(0.15, Config.clientPreviewAlphaEndValue, 0.0);
        assertOldKeysAbsent(yaml);
        assertBackupBytes(directory, "pre-5.3-budget-migration", original);
        Assert.assertEquals(Double.valueOf(12.0), ConfigDocument.read(QzMinerConfigSchema.SCHEMA,
                yaml.toPath()).get(QzMinerConfigSchema.TICK_BUDGET_MS));
    }

    @Test
    public void everyObsoleteBudgetKeyAloneTriggersMigration() throws Exception {
        String[] inputs = {"general:\n  maxBreakPerTick: 16\n",
                "general:\n  parallelTickServerWorkBudgetUnits: 320\n",
                "client:\n  parallelTickClientWorkBudgetUnits: 200\n"};
        for (int i = 0; i < inputs.length; i++) {
            File caseDir = caseDirectory("old-key-" + i);
            write(yaml(caseDir), inputs[i]);
            ConfigTestSupport.reset();
            ConfigBootstrap.bootstrap(caseDir, null);
            Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
            Assert.assertTrue(hasBackup(caseDir, "pre-5.3-budget-migration"));
            assertOldKeysAbsent(yaml(caseDir));
        }
    }

    @Test
    public void legacyDurationMatrixAndExplicitNewBudgetPrecedenceKeepOtherValues() throws Exception {
        String[][] cases = {{"10", "10"}, {"12.0", "12"}, {"41", "40"},
                {"2147483647", "40"}, {"9", "15"}, {"10.5", "15"}, {"'bad'", "15"}, {"null", "15"}};
        for (int i = 0; i < cases.length; i++) {
            File caseDir = caseDirectory("duration-" + i);
            write(yaml(caseDir), "general:\n  greeting: preserved\n  parallelTickMinDurationMs: " + cases[i][0] + "\n");
            ConfigTestSupport.reset();
            ConfigBootstrap.bootstrap(caseDir, null);
            Assert.assertEquals("preserved", Config.greeting);
            Assert.assertEquals(Integer.parseInt(cases[i][1]), Config.tickBudgetMs);
            assertOldKeysAbsent(yaml(caseDir));
        }
        File explicit = caseDirectory("explicit");
        write(yaml(explicit), "general:\n  tickBudgetMs: 25.0\n  parallelTickMinDurationMs: 'ignored legacy text'\n");
        ConfigTestSupport.reset();
        ConfigBootstrap.bootstrap(explicit, null);
        Assert.assertEquals(25, Config.tickBudgetMs);
        assertOldKeysAbsent(yaml(explicit));
    }

    @Test
    public void invalidYamlIsBackedUpByteForByteAndDoesNotReadOrRetireCfg() throws Exception {
        String[] invalid = {"this: [is: not: valid: yaml:::", "general:\n  chainRadius: 12.6\n",
                "general:\n  chainRadius: '42'\n", "general: nope\n", "client: null\n",
                "customRoot:\n  retained: keep-me\n", "general.chainRadius: 12\n",
                "client:\n  clientPreviewAlphaFadeStartRadius: 5\n  clientPreviewAlphaFadeEndRadius: 5\n",
                "general:\n  tickBudgetMs: null\n  parallelTickMinDurationMs: 12\n"};
        for (int i = 0; i < invalid.length; i++) {
            File caseDir = caseDirectory("invalid-" + i);
            File yaml = yaml(caseDir);
            write(yaml, invalid[i]);
            byte[] original = Files.readAllBytes(yaml.toPath());
            File cfg = legacyCfg(caseDir, 99, 2.0, 6.0, 12);
            byte[] originalCfg = Files.readAllBytes(cfg.toPath());
            ConfigTestSupport.reset();
            ConfigBootstrap.bootstrap(caseDir, cfg);
            Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
            assertBackupBytes(caseDir, "invalid", original);
            Assert.assertArrayEquals(originalCfg, Files.readAllBytes(cfg.toPath()));
            Assert.assertTrue(ConfigSemanticValidator.validate(ConfigDocument.read(QzMinerConfigSchema.SCHEMA,
                    yaml.toPath())).isValid());
        }
    }

    @Test
    public void emptyYamlUsesDefaultsWithoutWritingOrRetiringCfg() throws Exception {
        File yaml = yaml(directory);
        Files.write(yaml.toPath(), new byte[0]);
        File cfg = legacyCfg(directory, 99, 2.0, 6.0, 12);
        byte[] before = Files.readAllBytes(cfg.toPath());
        ConfigBootstrap.bootstrap(directory, cfg);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(0L, yaml.length());
        Assert.assertArrayEquals(before, Files.readAllBytes(cfg.toPath()));
    }

    @Test
    public void backupsNeverOverwritePreviousContent() throws Exception {
        File source = yaml(directory);
        write(source, "a: 1\n");
        File first = ConfigStartupMigration.backup(source, "test");
        write(source, "a: 2\n");
        File second = ConfigStartupMigration.backup(source, "test");
        Assert.assertNotEquals(first, second);
        Assert.assertEquals("a: 1\n", new String(Files.readAllBytes(first.toPath()), StandardCharsets.UTF_8));
        Assert.assertEquals("a: 2\n", new String(Files.readAllBytes(second.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void realBackupReadFailureKeepsOriginalAndPublishesNothing() throws Exception {
        File source = yaml(directory);
        write(source, "general: nope\n");
        byte[] before = Files.readAllBytes(source.toPath());
        try (SeekableByteChannel held = holdFile(source, "NOSHARE_READ")) {
            try {
                ConfigStartupMigration.backup(source, "unreadable");
                Assert.fail("实际文件共享锁必须阻止备份读取");
            } catch (IOException expected) {
                assertUnpublished();
            }
        }
        Assert.assertArrayEquals(before, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void realAtomicWriteFailuresKeepOriginalAndPublishNoBootstrapState() throws Exception {
        String[] inputs = {"general:\n  greeting: preserved\n  parallelTickMinDurationMs: 12\n", "general: nope\n"};
        for (int i = 0; i < inputs.length; i++) {
            File caseDir = caseDirectory("write-failure-" + i);
            File yaml = yaml(caseDir);
            write(yaml, inputs[i]);
            byte[] original = Files.readAllBytes(yaml.toPath());
            ConfigTestSupport.reset();
            Config.greeting = "sentinel";
            try (SeekableByteChannel held = holdFile(yaml, "NOSHARE_DELETE")) {
                try {
                    ConfigBootstrap.bootstrap(caseDir, null);
                    Assert.fail("实际共享锁必须阻止迁移/默认恢复的原子替换");
                } catch (IllegalStateException expected) {
                    Assert.assertEquals("sentinel", Config.greeting);
                    assertUnpublished();
                }
            }
            Assert.assertArrayEquals(original, Files.readAllBytes(yaml.toPath()));
            Assert.assertTrue(hasBackup(caseDir, i == 0 ? "pre-5.3-budget-migration" : "invalid"));
        }
    }

    @Test
    public void cfgMigrationClampsLegacyBudgetAndInvalidCandidateRestoresDefaults() throws Exception {
        int[][] cases = {{12, 12}, {100, 40}};
        for (int i = 0; i < cases.length; i++) {
            File caseDir = caseDirectory("cfg-" + i);
            File cfg = legacyCfg(caseDir, 33 + i, 2.0, 6.0, cases[i][0]);
            ConfigTestSupport.reset();
            ConfigBootstrap.bootstrap(caseDir, cfg);
            Assert.assertEquals(33 + i, Config.chainRadius);
            Assert.assertEquals(cases[i][1], Config.tickBudgetMs);
            Assert.assertFalse(cfg.exists());
            Assert.assertTrue(hasBackup(caseDir, "pre-import"));
            Assert.assertTrue(ConfigSemanticValidator.validate(ConfigDocument.read(QzMinerConfigSchema.SCHEMA,
                    yaml(caseDir).toPath())).isValid());
        }
        File invalidDir = caseDirectory("cfg-invalid");
        File invalid = legacyCfg(invalidDir, 33, 5.0, 5.0, 12);
        ConfigTestSupport.reset();
        ConfigBootstrap.bootstrap(invalidDir, invalid);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertFalse(invalid.exists());
        Assert.assertTrue(ConfigSemanticValidator.validate(ConfigDocument.read(QzMinerConfigSchema.SCHEMA,
                yaml(invalidDir).toPath())).isValid());
    }

    @Test
    public void realCfgRetireFailurePublishesNothingAndRetryUsesCompletedYaml() throws Exception {
        for (boolean valid : new boolean[] {true, false}) {
            File caseDir = caseDirectory("retire-" + valid);
            File cfg = legacyCfg(caseDir, 33, valid ? 2.0 : 5.0, valid ? 6.0 : 5.0, 12);
            byte[] original = Files.readAllBytes(cfg.toPath());
            ConfigTestSupport.reset();
            Config.greeting = "sentinel";
            try (SeekableByteChannel held = holdFile(cfg, "NOSHARE_DELETE")) {
                try {
                    ConfigBootstrap.bootstrap(caseDir, cfg);
                    Assert.fail("旧 cfg 未成功退役前不能发布");
                } catch (IllegalStateException expected) {
                    assertUnpublished();
                    Assert.assertEquals("sentinel", Config.greeting);
                }
            }
            Assert.assertArrayEquals(original, Files.readAllBytes(cfg.toPath()));
            Assert.assertTrue(yaml(caseDir).isFile());
            ConfigBootstrap.bootstrap(caseDir, cfg);
            Assert.assertEquals(valid ? 33 : QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
            Assert.assertEquals(valid ? 12 : QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
            Assert.assertArrayEquals(original, Files.readAllBytes(cfg.toPath()));
        }
    }

    @Test
    public void epochOverflowBeforeStartupLeavesDiskAndRuntimeUntouched() throws Exception {
        Field epoch = ConfigBootstrap.class.getDeclaredField("commitEpoch");
        epoch.setAccessible(true);
        long previous = epoch.getLong(null);
        Config.greeting = "sentinel";
        epoch.setLong(null, Long.MAX_VALUE);
        try {
            try {
                ConfigBootstrap.bootstrap(directory, null);
                Assert.fail("溢出必须在触盘/运行字段发布前失败");
            } catch (ConfigAuthorityInvariantError expected) {
                assertUnpublished();
                Assert.assertEquals("sentinel", Config.greeting);
                Assert.assertFalse(yaml(directory).exists());
            }
        } finally {
            epoch.setLong(null, previous);
        }
    }

    private File caseDirectory(String name) throws IOException {
        File result = new File(directory, name);
        Files.createDirectory(result.toPath());
        return result;
    }
    private static File yaml(File directory) {
        return new File(directory, ConfigBootstrap.YAML_FILE_NAME);
    }
    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static File legacyCfg(File directory, int radius, double start, double end, int duration) throws IOException {
        File cfg = new File(directory, "qz_miner.cfg");
        write(cfg, "general {\n I:chainRadius=" + radius + "\n I:parallelTickMinDurationMs=" + duration
                + "\n}\nclient {\n D:clientPreviewAlphaFadeStartRadius=" + start
                + "\n D:clientPreviewAlphaFadeEndRadius=" + end + "\n}\n");
        return cfg;
    }
    private static void assertOldKeysAbsent(File yaml) throws IOException {
        String text = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
        for (String key : new String[] {"maxBreakPerTick", "parallelTickMinDurationMs",
                "parallelTickServerWorkBudgetUnits", "parallelTickClientWorkBudgetUnits"}) {
            Assert.assertFalse(text.contains(key));
        }
    }
    private static boolean hasBackup(File directory, String marker) {
        for (File file : directory.listFiles()) if (file.getName().contains(marker) && file.getName().endsWith(".bak")) return true;
        return false;
    }
    private static void assertBackupBytes(File directory, String marker, byte[] expected) throws IOException {
        for (File file : directory.listFiles()) {
            if (file.getName().contains(marker) && file.getName().endsWith(".bak")) {
                Assert.assertArrayEquals(expected, Files.readAllBytes(file.toPath()));
                return;
            }
        }
        Assert.fail("缺少原内容备份: " + marker);
    }
    private static void assertUnpublished() {
        Assert.assertNull(ConfigBootstrap.document());
        Assert.assertNull(ConfigBootstrap.yamlFile());
        try {
            ConfigBootstrap.currentCommittedSnapshot();
            Assert.fail("启动失败不能产生 current");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("not initialized"));
        }
    }
    private static SeekableByteChannel holdFile(File file, String option) throws Exception {
        // Windows 实际共享标志提供确定失败；其它平台仍运行非空目标目录的通用写失败反例。
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        OpenOption sharing = (OpenOption) Class.forName("com.sun.nio.file.ExtendedOpenOption").getField(option).get(null);
        return Files.newByteChannel(file.toPath(), new HashSet<OpenOption>(Arrays.asList(StandardOpenOption.READ, sharing)));
    }
    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
