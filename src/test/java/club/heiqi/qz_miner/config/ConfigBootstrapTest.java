package club.heiqi.qz_miner.config;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.qz_miner.Config;
import cpw.mods.fml.relauncher.FMLInjectionData;

/**
 * Bootstrap 磁盘事务 / 幂等 / 坏 YAML 恢复（纯 JVM）。
 */
public class ConfigBootstrapTest {

    private File tempDir;
    private Field minecraftHomeField;
    private Object previousMinecraftHome;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-bootstrap-").toFile();
        minecraftHomeField = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHomeField.setAccessible(true);
        previousMinecraftHome = minecraftHomeField.get(null);
        minecraftHomeField.set(null, tempDir);
        ConfigBootstrap.resetForTests();
        resetStaticDefaults();
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        resetStaticDefaults();
        try {
            minecraftHomeField.set(null, previousMinecraftHome);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        deleteRecursively(tempDir);
    }

    @Test
    public void noYamlNoCfgPersistsDefaultYamlAndAppliesDefaults() {
        File cfg = new File(tempDir, "qz_miner.cfg");
        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Assert.assertTrue("default YAML should be written", yaml.isFile() && yaml.length() > 0);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, Config.greeting);
        Assert.assertTrue(Config.clientEnablePreviewRender);
        Assert.assertTrue(Config.autoToolSwapEnabled);
        Assert.assertTrue(Config.autoToolTakeoverEnabled);
        Assert.assertTrue(Config.autoToolPrioritySelectors.isEmpty());
        Assert.assertSame(manager, ConfigBootstrap.manager());
        Assert.assertNotNull(ConfigBootstrap.currentValidatedSnapshot());
    }

    @Test
    public void existingValidYamlBootstraps() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        String body = ""
                + "general:\n"
                + "  greeting: FromYaml\n"
                + "  chainRadius: 32\n"
                + "  chainMaxBlocks: 512\n"
                + "  chainLoggingShellLayers: 2\n"
                + "  maxBreakPerTick: 16\n"
                + "  cableReplaceMaxPerTick: 256\n"
                + "  chainWatchdogTimeoutTicks: 40\n"
                + "  parallelTickMinDurationMs: 12\n"
                + "  parallelTickServerWorkBudgetUnits: 320\n"
                + "  enableUnlimitedOreFortune: true\n"
                + "  enableFortuneForPlacedOre: true\n"
                + "client:\n"
                + "  clientEnablePreviewRender: false\n"
                + "  autoToolSwapEnabled: false\n"
                + "  autoToolTakeoverEnabled: false\n"
                + "  autoToolPrioritySelectors: [ore:toolPickaxe, 'mod:drill@4']\n"
                + "  parallelTickClientWorkBudgetUnits: 200\n"
                + "  clientPreviewMaxRadius: 8\n"
                + "  clientPreviewMaxTargets: 128\n"
                + "  clientPreviewAlphaFadeStartRadius: 1.5\n"
                + "  clientPreviewAlphaFadeEndRadius: 4.0\n"
                + "  clientPreviewAlphaStartValue: 0.6\n"
                + "  clientPreviewAlphaEndValue: 0.1\n";
        Files.write(yaml.toPath(), body.getBytes(StandardCharsets.UTF_8));

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, new File(tempDir, "missing.cfg"));

        Assert.assertNotNull(manager);
        Assert.assertEquals("FromYaml", Config.greeting);
        Assert.assertEquals(32, Config.chainRadius);
        Assert.assertEquals(512, Config.chainMaxBlocks);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertFalse(Config.autoToolSwapEnabled);
        Assert.assertFalse(Config.autoToolTakeoverEnabled);
        Assert.assertEquals("ore:toolPickaxe", Config.autoToolPrioritySelectors.get(0).canonicalText());
        Assert.assertEquals("mod:drill@4", Config.autoToolPrioritySelectors.get(1).canonicalText());
        Assert.assertEquals(8, Config.clientPreviewMaxRadius);
        Assert.assertTrue(Config.enableUnlimitedOreFortune);
    }

    @Test
    public void syntaxInvalidYamlIsBackedUpAndDefaultsRestoredWithoutReadingCfg() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "this: [is: not: valid: yaml:::".getBytes(StandardCharsets.UTF_8));
        File cfg = writeLegacyCfg(99, 2.0D, 6.0D);

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, Config.greeting);
        Assert.assertTrue(yaml.isFile() && yaml.length() > 0);

        int backups = 0;
        File[] children = tempDir.listFiles();
        Assert.assertNotNull(children);
        for (File child : children) {
            if (child.getName().contains("invalid") && child.getName().endsWith(".bak")) {
                backups++;
            }
        }
        Assert.assertTrue("invalid yaml unique backup expected", backups >= 1);
        Assert.assertTrue("existing YAML path must not read/retire cfg", cfg.isFile());
    }

    @Test
    public void emptyYamlUsesSchemaDefaultsAndLeavesLegacyCfgUntouched() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), new byte[0]);
        File cfg = writeLegacyCfg(99, 2.0D, 6.0D);
        byte[] cfgBefore = Files.readAllBytes(cfg.toPath());

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        Assert.assertSame(manager, ConfigBootstrap.manager());
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, Config.greeting);
        Assert.assertTrue(ConfigSemanticValidator.captureAndValidate(manager).isValid());
        Assert.assertTrue("empty YAML authority must not retire cfg", cfg.isFile());
        Assert.assertArrayEquals(cfgBefore, Files.readAllBytes(cfg.toPath()));
    }

    @Test
    public void secondBootstrapSamePathIsIdempotent() {
        ConfigManager first = ConfigBootstrap.bootstrap(tempDir, null);
        ConfigManager second = ConfigBootstrap.bootstrap(tempDir, null);
        Assert.assertSame(first, second);
    }

    @Test(expected = IllegalStateException.class)
    public void secondBootstrapDifferentPathFailsFast() throws Exception {
        ConfigBootstrap.bootstrap(tempDir, null);
        File other = Files.createTempDirectory("qz-miner-other-").toFile();
        try {
            ConfigBootstrap.bootstrap(other, null);
        } finally {
            deleteRecursively(other);
        }
    }

    @Test
    public void requiredBackupNeverOverwritesExisting() throws Exception {
        File src = new File(tempDir, "sample.yaml");
        Files.write(src.toPath(), "a: 1\n".getBytes(StandardCharsets.UTF_8));
        File b1 = ConfigBootstrap.requiredBackup(src, "t");
        // 强制同毫秒冲突：再备份应得到不同路径
        File b2 = ConfigBootstrap.requiredBackup(src, "t");
        Assert.assertTrue(b1.isFile());
        Assert.assertTrue(b2.isFile());
        Assert.assertFalse(b1.getAbsolutePath().equals(b2.getAbsolutePath()));
    }

    @Test
    public void semanticInvalidExistingYamlBacksUpAndRestoresDefaults() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        String body = ""
                + "general:\n"
                + "  greeting: Bad\n"
                + "  chainRadius: 12.6\n"
                + "  chainMaxBlocks: 1024\n"
                + "  chainLoggingShellLayers: 1\n"
                + "  maxBreakPerTick: 64\n"
                + "  cableReplaceMaxPerTick: 1024\n"
                + "  chainWatchdogTimeoutTicks: 50\n"
                + "  parallelTickMinDurationMs: 15\n"
                + "  parallelTickServerWorkBudgetUnits: 640\n"
                + "  enableUnlimitedOreFortune: false\n"
                + "  enableFortuneForPlacedOre: false\n"
                + "client:\n"
                + "  clientEnablePreviewRender: true\n"
                + "  parallelTickClientWorkBudgetUnits: 640\n"
                + "  clientPreviewMaxRadius: 16\n"
                + "  clientPreviewMaxTargets: 1024\n"
                + "  clientPreviewAlphaFadeStartRadius: 2.0\n"
                + "  clientPreviewAlphaFadeEndRadius: 6.0\n"
                + "  clientPreviewAlphaStartValue: 0.78\n"
                + "  clientPreviewAlphaEndValue: 0.15\n";
        Files.write(yaml.toPath(), body.getBytes(StandardCharsets.UTF_8));
        ConfigBootstrap.bootstrap(tempDir, null);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertTrue(hasBackupContaining("invalid"));
    }

    @Test
    public void rawTypeInvalidExistingYamlBacksUpAndRestoresDefaults() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "general:\n  chainRadius: '42'\n".getBytes(StandardCharsets.UTF_8));

        ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertTrue(hasBackupContaining("invalid"));
        Assert.assertTrue(RawYamlPreflight.validate(yaml, QzMinerConfigSchema.create()).isValid());
    }

    @Test
    public void backupFailureDoesNotDeleteOriginalYaml() throws Exception {
        final File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        final byte[] original = "general: nope\n".getBytes(StandardCharsets.UTF_8);
        Files.write(yaml.toPath(), original);
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        ConfigBootstrap.setBackupCopierForTests(new ConfigBootstrap.BackupCopier() {
            @Override
            public void copy(File source, File target) throws IOException {
                throw new IOException("forced backup failure");
            }
        });

        try {
            ConfigBootstrap.bootstrap(tempDir, null);
            Assert.fail("backup failure must fail fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("backup"));
        }
        Assert.assertTrue(yaml.isFile());
        Assert.assertArrayEquals(original, Files.readAllBytes(yaml.toPath()));
        assertBootstrapStateUnpublished(before);
    }

    @Test
    public void defaultRecoveryFailurePublishesNoBootstrapStateOrRuntimeStatics() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "general: nope\n".getBytes(StandardCharsets.UTF_8));
        setStaticSentinels();
        final Object[] before = captureRuntimeConfigValues();
        ConfigBootstrap.setDefaultPersisterForTests(new ConfigBootstrap.DefaultPersister() {
            @Override
            public ConfigBootstrap.StrictLoad persist(File file, ConfigSchema schema, String reason) {
                throw new IllegalStateException("forced default recovery failure");
            }
        });

        try {
            ConfigBootstrap.bootstrap(tempDir, null);
            Assert.fail("default recovery failure must propagate");
        } catch (IllegalStateException expected) {
            Assert.assertEquals("forced default recovery failure", expected.getMessage());
        }

        assertBootstrapStateUnpublished(before);
    }

    @Test
    public void validLegacyCfgMigratesThroughValidatorAndRetiresCfg() throws Exception {
        File cfg = writeLegacyCfg(33, 2.0D, 6.0D);

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        Assert.assertEquals(33, Config.chainRadius);
        Assert.assertFalse(cfg.exists());
        Assert.assertTrue(RawYamlPreflight.validate(ConfigBootstrap.yamlFile(), manager.schema()).isValid());
        Assert.assertTrue(ConfigSemanticValidator.captureAndValidate(manager).isValid());
    }

    @Test
    public void semanticInvalidLegacyMigrationRebuildsDefaultsAndRetiresCfg() throws Exception {
        File cfg = writeLegacyCfg(33, 5.0D, 5.0D);

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertFalse(cfg.exists());
        Assert.assertTrue(ConfigSemanticValidator.captureAndValidate(manager).isValid());
    }

    @Test
    public void successfulMigrationRetireFailurePublishesNothingAndRetryUsesYamlAuthority() throws Exception {
        File cfg = writeLegacyCfg(33, 2.0D, 6.0D);
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        forceCfgRetireFailure();

        assertBootstrapRetireFailure(cfg, before);

        ConfigBootstrap.setCfgRetirerForTests(null);
        ConfigManager retried = ConfigBootstrap.bootstrap(tempDir, cfg);
        Assert.assertNotNull(retried);
        Assert.assertSame(retried, ConfigBootstrap.manager());
        Assert.assertEquals("retry must load migrated YAML instead of cached half-init/default cfg path",
                33, Config.chainRadius);
        Assert.assertTrue("authoritative YAML retry must ignore remaining cfg", cfg.isFile());
    }

    @Test
    public void failedMigrationRecoveryRetireFailurePublishesNothing() throws Exception {
        File cfg = writeLegacyCfg(33, 5.0D, 5.0D);
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        forceCfgRetireFailure();

        assertBootstrapRetireFailure(cfg, before);
        Assert.assertTrue("default recovery YAML should exist for a later authoritative retry",
                new File(tempDir, ConfigBootstrap.YAML_FILE_NAME).isFile());

        ConfigBootstrap.setCfgRetirerForTests(null);
        ConfigManager retried = ConfigBootstrap.bootstrap(tempDir, cfg);
        Assert.assertNotNull(retried);
        Assert.assertSame(retried, ConfigBootstrap.manager());
        Assert.assertEquals("retry must load recovered default YAML as authority",
                QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertTrue("authoritative retry must leave failed-import cfg untouched", cfg.isFile());
    }

    @Test
    public void epochOverflowBeforeApplyAllPublishesNothing() throws Exception {
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        long epochBefore = 0L;
        // 若此前已有 bootstrap 测试推进过 epoch，只记录；钩子设到天花板
        ConfigBootstrap.setCommitEpochForTests(Long.MAX_VALUE);

        try {
            ConfigBootstrap.bootstrap(tempDir, null);
            Assert.fail("epoch overflow must fail before any publication");
        } catch (ConfigAuthorityInvariantError expected) {
            Assert.assertTrue(expected.getMessage().contains("epoch overflow"));
        }

        assertBootstrapStateUnpublished(before);
        // reset 不回退 epoch；套件隔离用测试钩子抬到可用正值（非 reset 回退）
        ConfigBootstrap.resetForTests();
        ConfigBootstrap.setCommitEpochForTests(Math.max(epochBefore, 1_000_000_000L));
    }

    @Test
    public void resetForTestsNeverRollsCommitEpochBack() {
        ConfigBootstrap.bootstrap(tempDir, null);
        long firstEpoch = ConfigBootstrap.currentCommittedSnapshot().epoch;

        ConfigBootstrap.resetForTests();
        ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertTrue(ConfigBootstrap.currentCommittedSnapshot().epoch > firstEpoch);
    }

    @Test
    public void wrongManagerCaptureIsFailStopWithoutChangingCurrentEpochOrRuntime() throws Exception {
        ConfigBootstrap.bootstrap(tempDir, null);
        CommittedSnapshot beforeCommitted = ConfigBootstrap.currentCommittedSnapshot();
        Object[] beforeRuntime = captureRuntimeConfigValues();
        ConfigManager wrong = ConfigManager.bootstrap(
                new File(tempDir, "wrong.yaml"),
                QzMinerConfigSchema.create(),
                ConfigSemanticValidator.draftValidator());

        try {
            ConfigBootstrap.captureCommittedSnapshot(wrong);
            Assert.fail("wrong manager capture must fail-stop");
        } catch (ConfigAuthorityInvariantError expected) {
            Assert.assertTrue(expected.getMessage().contains("identity mismatch"));
        }

        Assert.assertSame(beforeCommitted, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(beforeCommitted.epoch, ConfigBootstrap.currentCommittedSnapshot().epoch);
        Assert.assertArrayEquals(beforeRuntime, captureRuntimeConfigValues());
    }

    @Test
    public void migrationPartialYamlIsBackedUpBeforeDefaultRebuildAndRevalidated() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "general:\n  chainRadius: 'partial'\n".getBytes(StandardCharsets.UTF_8));

        ConfigBootstrap.cleanupPartialYamlIfAny(yaml);
        Assert.assertFalse(yaml.exists());
        Assert.assertTrue(hasBackupContaining("partial"));
        ConfigBootstrap.StrictLoad defaults = ConfigBootstrap.persistDefaultsStrict(
                yaml, QzMinerConfigSchema.create(), "migration test recovery");

        Assert.assertTrue(defaults.isValid());
        Assert.assertTrue(RawYamlPreflight.validate(yaml, defaults.manager.schema()).isValid());
        Assert.assertTrue(ConfigSemanticValidator.captureAndValidate(defaults.manager).isValid());
    }

    private File writeLegacyCfg(int radius, double fadeStart, double fadeEnd) throws Exception {
        File cfg = new File(tempDir, "qz_miner.cfg");
        String body = "general {\n"
                + "  I:chainRadius=" + radius + "\n"
                + "}\n"
                + "client {\n"
                + "  D:clientPreviewAlphaFadeStartRadius=" + fadeStart + "\n"
                + "  D:clientPreviewAlphaFadeEndRadius=" + fadeEnd + "\n"
                + "}\n";
        Files.write(cfg.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return cfg;
    }

    private boolean hasBackupContaining(String marker) {
        File[] children = tempDir.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.getName().contains(marker) && child.getName().endsWith(".bak")) {
                return true;
            }
        }
        return false;
    }

    private void forceCfgRetireFailure() {
        ConfigBootstrap.setCfgRetirerForTests(new ConfigBootstrap.CfgRetirer() {
            @Override
            public void move(File source, File target) throws IOException {
                throw new IOException("forced cfg retire failure");
            }
        });
    }

    private void assertBootstrapRetireFailure(File cfg, Object[] before) {
        try {
            ConfigBootstrap.bootstrap(tempDir, cfg);
            Assert.fail("cfg retire failure must fail the first bootstrap");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("retire legacy cfg"));
        }
        assertBootstrapStateUnpublished(before);
        Assert.assertTrue("failed retirement must preserve cfg", cfg.isFile());
    }

    private static void assertBootstrapStateUnpublished(Object[] before) {
        Assert.assertNull("manager must not publish before transaction commit", ConfigBootstrap.manager());
        Assert.assertNull("yamlFile must publish only with a committed manager", ConfigBootstrap.yamlFile());
        try {
            ConfigBootstrap.currentValidatedSnapshot();
            Assert.fail("current snapshot must remain fail-fast before transaction commit");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("not initialized"));
        }
        Assert.assertArrayEquals("all 21 Config runtime fields must remain untouched", before,
                captureRuntimeConfigValues());
    }

    private static void setStaticSentinels() {
        Config.greeting = "sentinel";
        Config.chainRadius = 901;
        Config.chainMaxBlocks = 902;
        Config.chainLoggingShellLayers = 903;
        Config.maxBreakPerTick = 904;
        Config.cableReplaceMaxPerTick = 905;
        Config.chainWatchdogTimeoutTicks = 906;
        Config.parallelTickMinDurationMs = 907;
        Config.parallelTickServerWorkBudgetUnits = 908;
        Config.enableUnlimitedOreFortune = true;
        Config.enableFortuneForPlacedOre = true;
        Config.clientEnablePreviewRender = false;
        Config.autoToolSwapEnabled = false;
        Config.autoToolTakeoverEnabled = false;
        Config.autoToolPrioritySelectors = java.util.Collections.singletonList(
                club.heiqi.qz_miner.toolswap.ToolSelectorParser.parse("sentinel:item@*"));
        Config.parallelTickClientWorkBudgetUnits = 909;
        Config.clientPreviewMaxRadius = 910;
        Config.clientPreviewMaxTargets = 911;
        Config.clientPreviewAlphaFadeStartRadius = 912.0D;
        Config.clientPreviewAlphaFadeEndRadius = 913.0D;
        Config.clientPreviewAlphaStartValue = 0.91D;
        Config.clientPreviewAlphaEndValue = 0.92D;
    }

    private static Object[] captureRuntimeConfigValues() {
        return new Object[] {
                Config.greeting,
                Integer.valueOf(Config.chainRadius),
                Integer.valueOf(Config.chainMaxBlocks),
                Integer.valueOf(Config.chainLoggingShellLayers),
                Integer.valueOf(Config.maxBreakPerTick),
                Integer.valueOf(Config.cableReplaceMaxPerTick),
                Integer.valueOf(Config.chainWatchdogTimeoutTicks),
                Integer.valueOf(Config.parallelTickMinDurationMs),
                Integer.valueOf(Config.parallelTickServerWorkBudgetUnits),
                Boolean.valueOf(Config.enableUnlimitedOreFortune),
                Boolean.valueOf(Config.enableFortuneForPlacedOre),
                Boolean.valueOf(Config.clientEnablePreviewRender),
                Boolean.valueOf(Config.autoToolSwapEnabled),
                Boolean.valueOf(Config.autoToolTakeoverEnabled),
                Config.autoToolPrioritySelectors,
                Integer.valueOf(Config.parallelTickClientWorkBudgetUnits),
                Integer.valueOf(Config.clientPreviewMaxRadius),
                Integer.valueOf(Config.clientPreviewMaxTargets),
                Double.valueOf(Config.clientPreviewAlphaFadeStartRadius),
                Double.valueOf(Config.clientPreviewAlphaFadeEndRadius),
                Double.valueOf(Config.clientPreviewAlphaStartValue),
                Double.valueOf(Config.clientPreviewAlphaEndValue)
        };
    }

    private static void resetStaticDefaults() {
        Config.greeting = QzMinerConfigDefaults.GREETING;
        Config.chainRadius = QzMinerConfigDefaults.CHAIN_RADIUS;
        Config.chainMaxBlocks = QzMinerConfigDefaults.CHAIN_MAX_BLOCKS;
        Config.chainLoggingShellLayers = QzMinerConfigDefaults.CHAIN_LOGGING_SHELL_LAYERS;
        Config.maxBreakPerTick = QzMinerConfigDefaults.MAX_BREAK_PER_TICK;
        Config.cableReplaceMaxPerTick = QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK;
        Config.chainWatchdogTimeoutTicks = QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS;
        Config.parallelTickMinDurationMs = QzMinerConfigDefaults.PARALLEL_TICK_MIN_DURATION_MS;
        Config.parallelTickServerWorkBudgetUnits = QzMinerConfigDefaults.PARALLEL_TICK_SERVER_WORK_BUDGET_UNITS;
        Config.enableUnlimitedOreFortune = QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE;
        Config.enableFortuneForPlacedOre = QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE;
        Config.clientEnablePreviewRender = QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER;
        Config.autoToolSwapEnabled = QzMinerConfigDefaults.CLIENT_AUTO_TOOL_SWAP_ENABLED;
        Config.autoToolPrioritySelectors = java.util.Collections.emptyList();
        Config.parallelTickClientWorkBudgetUnits = QzMinerConfigDefaults.PARALLEL_TICK_CLIENT_WORK_BUDGET_UNITS;
        Config.clientPreviewMaxRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_RADIUS;
        Config.clientPreviewMaxTargets = QzMinerConfigDefaults.CLIENT_PREVIEW_MAX_TARGETS;
        Config.clientPreviewAlphaFadeStartRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_START_RADIUS;
        Config.clientPreviewAlphaFadeEndRadius = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_FADE_END_RADIUS;
        Config.clientPreviewAlphaStartValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_START_VALUE;
        Config.clientPreviewAlphaEndValue = QzMinerConfigDefaults.CLIENT_PREVIEW_ALPHA_END_VALUE;
        Config.configPath = "";
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
