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
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import cpw.mods.fml.relauncher.FMLInjectionData;

/** Bootstrap 默认、运行时发布与 5.2 配置窄迁移（纯 JVM）。 */
public class ConfigBootstrapTest {

    private static final String[] LEGACY_BUDGET_KEYS = {
            "maxBreakPerTick",
            "parallelTickMinDurationMs",
            "parallelTickServerWorkBudgetUnits",
            "parallelTickClientWorkBudgetUnits"
    };

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
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertTrue(Config.clientEnablePreviewRender);
        Assert.assertTrue(Config.autoToolSwapEnabled);
        Assert.assertTrue(Config.autoToolPrioritySelectors.isEmpty());
        Assert.assertSame(manager, ConfigBootstrap.manager());
        Assert.assertNotNull(ConfigBootstrap.currentValidatedSnapshot());
    }

    @Test
    public void existingNormalizedYamlAppliesRuntimeValues() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        String body = ""
                + "general:\n"
                + "  greeting: RuntimeYaml\n"
                + "  chainRadius: 32\n"
                + "  chainMaxBlocks: 512\n"
                + "  chainLoggingShellLayers: 2\n"
                + "  cableReplaceMaxPerTick: 256\n"
                + "  chainWatchdogTimeoutTicks: 40\n"
                + "  tickBudgetMs: 22\n"
                + "  enableUnlimitedOreFortune: true\n"
                + "  enableFortuneForPlacedOre: true\n"
                + "client:\n"
                + "  clientEnablePreviewRender: false\n"
                + "  tunnelDirectionSource: hit_face\n"
                + "  autoToolSwapEnabled: false\n"
                + "  autoToolPrioritySelectors: [ore:toolPickaxe, 'mod:drill@4']\n"
                + "  clientPreviewMaxRadius: 8\n"
                + "  clientPreviewMaxTargets: 128\n"
                + "  clientPreviewAlphaFadeStartRadius: 1.5\n"
                + "  clientPreviewAlphaFadeEndRadius: 4.0\n"
                + "  clientPreviewAlphaStartValue: 0.6\n"
                + "  clientPreviewAlphaEndValue: 0.1\n";
        Files.write(yaml.toPath(), body.getBytes(StandardCharsets.UTF_8));

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertNotNull(manager);
        Assert.assertEquals("RuntimeYaml", Config.greeting);
        Assert.assertEquals(32, Config.chainRadius);
        Assert.assertEquals(512, Config.chainMaxBlocks);
        Assert.assertEquals(22, Config.tickBudgetMs);
        Assert.assertTrue(Config.enableUnlimitedOreFortune);
        Assert.assertTrue(Config.enableFortuneForPlacedOre);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, Config.tunnelDirectionSource);
        Assert.assertFalse(Config.autoToolSwapEnabled);
        Assert.assertEquals("ore:toolPickaxe", Config.autoToolPrioritySelectors.get(0).canonicalText());
        Assert.assertEquals("mod:drill@4", Config.autoToolPrioritySelectors.get(1).canonicalText());
        Assert.assertEquals(8, Config.clientPreviewMaxRadius);
        Assert.assertEquals(0.6D, Config.clientPreviewAlphaStartValue, 0.0D);
        Assert.assertFalse(hasBackupContaining(tempDir, "pre-5.3-budget-migration"));
    }

    @Test
    public void legacyYamlMigrationPreservesBusinessFieldsAndRemovesAllFourKeys() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        String body = ""
                + "general:\n"
                + "  greeting: MigratedYaml\n"
                + "  chainRadius: 31\n"
                + "  maxBreakPerTick: 16\n"
                + "  parallelTickMinDurationMs: 12\n"
                + "  parallelTickServerWorkBudgetUnits: 320\n"
                + "  enableUnlimitedOreFortune: true\n"
                + "client:\n"
                + "  clientEnablePreviewRender: false\n"
                + "  tunnelDirectionSource: hit_face\n"
                + "  parallelTickClientWorkBudgetUnits: 200\n"
                + "  clientPreviewAlphaStartValue: 0.78\n"
                + "  clientPreviewAlphaEndValue: 0.15\n"
                + "customRoot:\n"
                + "  retained: keep-me\n"
                + "  ratio: 0.125\n"
                + "  samples: [7, 0.25, 1.0, 9223372036854775807, -9223372036854775808]\n"
                + "custom.ratio: 0.375\n";
        Files.write(yaml.toPath(), body.getBytes(StandardCharsets.UTF_8));

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertNotNull(manager);
        Assert.assertEquals("MigratedYaml", Config.greeting);
        Assert.assertEquals(31, Config.chainRadius);
        Assert.assertEquals(12, Config.tickBudgetMs);
        Assert.assertTrue(Config.enableUnlimitedOreFortune);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, Config.tunnelDirectionSource);
        Assert.assertEquals(0.78D, Config.clientPreviewAlphaStartValue, 0.0D);
        Assert.assertEquals(0.15D, Config.clientPreviewAlphaEndValue, 0.0D);
        club.heiqi.config.ConfigNode migratedRoot = club.heiqi.config.Config.load(yaml);
        Assert.assertEquals(0.125D, migratedRoot.get("customRoot.ratio").asDouble(), 0.0D);
        Assert.assertEquals(7L, migratedRoot.get("customRoot.samples").get(0).asLong());
        Assert.assertEquals(0.25D, migratedRoot.get("customRoot.samples").get(1).asDouble(), 0.0D);
        Assert.assertEquals(1.0D, migratedRoot.get("customRoot.samples").get(2).asDouble(), 0.0D);
        Assert.assertEquals("1.0", migratedRoot.get("customRoot.samples").get(2).asString());
        Assert.assertEquals(Long.MAX_VALUE, migratedRoot.get("customRoot.samples").get(3).asLong());
        Assert.assertEquals(Long.MIN_VALUE, migratedRoot.get("customRoot.samples").get(4).asLong());
        Assert.assertTrue(migratedRoot.asMap().containsKey("custom.ratio"));
        Assert.assertEquals(0.375D, migratedRoot.asMap().get("custom.ratio").asDouble(), 0.0D);
        String migrated = readUtf8(yaml);
        Assert.assertTrue(migrated.contains("tickBudgetMs: 12"));
        Assert.assertTrue("non-schema overlay should remain", migrated.contains("keep-me"));
        assertLegacyBudgetKeysRemoved(migrated);
        Assert.assertTrue(hasBackupContaining(tempDir, "pre-5.3-budget-migration"));
    }

    @Test
    public void eachLegacyBudgetKeyTriggersMigration() throws Exception {
        String[] yamlBodies = {
                "general:\n  maxBreakPerTick: 16\n",
                "general:\n  parallelTickServerWorkBudgetUnits: 320\n",
                "client:\n  parallelTickClientWorkBudgetUnits: 200\n"
        };

        for (int index = 0; index < yamlBodies.length; index++) {
            File caseDir = createCaseDir("legacy-key-" + index);
            File yaml = new File(caseDir, ConfigBootstrap.YAML_FILE_NAME);
            Files.write(yaml.toPath(), yamlBodies[index].getBytes(StandardCharsets.UTF_8));

            resetBootstrapState();
            Assert.assertNotNull(ConfigBootstrap.bootstrap(caseDir, null));

            Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
            assertLegacyBudgetKeysRemoved(readUtf8(yaml));
            Assert.assertTrue(hasBackupContaining(caseDir, "pre-5.3-budget-migration"));
        }
    }

    @Test
    public void legacyYamlDurationMigrationMatrix() throws Exception {
        String[][] cases = {
                {"10", "10"},
                {"12", "12"},
                {"41", "40"},
                {"2147483647", "40"},
                {"9", "15"},
                {"10.5", "15"},
                {"'bad'", "15"},
                {"null", "15"}
        };

        for (int index = 0; index < cases.length; index++) {
            File caseDir = createCaseDir("duration-" + index);
            File yaml = new File(caseDir, ConfigBootstrap.YAML_FILE_NAME);
            Files.write(yaml.toPath(), ("general:\n  parallelTickMinDurationMs: "
                    + cases[index][0] + "\n").getBytes(StandardCharsets.UTF_8));

            resetBootstrapState();
            Assert.assertNotNull(ConfigBootstrap.bootstrap(caseDir, null));

            int expected = Integer.parseInt(cases[index][1]);
            Assert.assertEquals(expected, Config.tickBudgetMs);
            Assert.assertTrue(readUtf8(yaml).contains("tickBudgetMs: " + expected));
            assertLegacyBudgetKeysRemoved(readUtf8(yaml));
        }
    }

    @Test
    public void explicitTickBudgetWinsWhileLegacyKeysAreRemoved() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), ("general:\n"
                + "  tickBudgetMs: 25\n"
                + "  parallelTickMinDurationMs: 'ignored legacy text'\n"
                + "  maxBreakPerTick: 64\n"
                + "  parallelTickServerWorkBudgetUnits: 320\n"
                + "client:\n"
                + "  parallelTickClientWorkBudgetUnits: 640\n").getBytes(StandardCharsets.UTF_8));

        ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertEquals(25, Config.tickBudgetMs);
        String migrated = readUtf8(yaml);
        Assert.assertTrue(migrated.contains("tickBudgetMs: 25"));
        assertLegacyBudgetKeysRemoved(migrated);
    }

    @Test
    public void migrationBackupFailurePreservesOriginalAndPublishesNothing() throws Exception {
        final File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        final byte[] original = ("general:\n"
                + "  greeting: KeepOriginal\n"
                + "  parallelTickMinDurationMs: 12\n").getBytes(StandardCharsets.UTF_8);
        Files.write(yaml.toPath(), original);
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        ConfigBootstrap.setBackupCopierForTests(new ConfigBootstrap.BackupCopier() {
            @Override
            public void copy(File source, File target) throws IOException {
                throw new IOException("forced migration backup failure");
            }
        });

        try {
            ConfigBootstrap.bootstrap(tempDir, null);
            Assert.fail("migration backup failure must fail fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("backup"));
        }

        Assert.assertArrayEquals(original, Files.readAllBytes(yaml.toPath()));
        assertBootstrapStateUnpublished(before);
    }

    @Test
    public void migrationSaveFailurePreservesOriginalAndPublishesNothing() throws Exception {
        final File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        final byte[] original = ("general:\n"
                + "  greeting: KeepOriginal\n"
                + "  parallelTickMinDurationMs: 12\n"
                + "client:\n"
                + "  clientPreviewAlphaStartValue: 0.78\n"
                + "  clientPreviewAlphaEndValue: 0.15\n").getBytes(StandardCharsets.UTF_8);
        Files.write(yaml.toPath(), original);
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        ConfigBootstrap.setBudgetMigrationSaverForTests(new ConfigBootstrap.BudgetMigrationSaver() {
            @Override
            public void save(File file, String migrated) throws IOException {
                throw new IOException("forced migration save failure");
            }
        });

        try {
            ConfigBootstrap.bootstrap(tempDir, null);
            Assert.fail("migration save failure must fail fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("migrate 5.2 YAML budgets"));
        }

        Assert.assertArrayEquals(original, Files.readAllBytes(yaml.toPath()));
        Assert.assertTrue(hasBackupContaining(tempDir, "pre-5.3-budget-migration"));
        assertBootstrapStateUnpublished(before);
    }

    @Test
    public void legacyDurationNormalizationUses52Domains() {
        Assert.assertEquals(10, ConfigBootstrap.normalizeLegacyTickDuration(10.0D));
        Assert.assertEquals(40, ConfigBootstrap.normalizeLegacyTickDuration(40.0D));
        Assert.assertEquals(40, ConfigBootstrap.normalizeLegacyTickDuration(41.0D));
        Assert.assertEquals(40, ConfigBootstrap.normalizeLegacyTickDuration(Integer.MAX_VALUE));
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS,
                ConfigBootstrap.normalizeLegacyTickDuration(9.0D));
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS,
                ConfigBootstrap.normalizeLegacyTickDuration(10.5D));
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS,
                ConfigBootstrap.normalizeLegacyTickDuration(Double.NaN));
        Assert.assertEquals(10, ConfigBootstrap.normalizeLegacyCfgTickDuration(5));
        Assert.assertEquals(12, ConfigBootstrap.normalizeLegacyCfgTickDuration(12));
        Assert.assertEquals(40, ConfigBootstrap.normalizeLegacyCfgTickDuration(100));
    }

    @Test
    public void syntaxInvalidYamlIsBackedUpAndDefaultsRestoredWithoutReadingCfg() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "this: [is: not: valid: yaml:::".getBytes(StandardCharsets.UTF_8));
        File cfg = writeLegacyCfg(tempDir, 99, 2.0D, 6.0D, 12);

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, Config.greeting);
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertTrue(yaml.isFile() && yaml.length() > 0);
        Assert.assertTrue(hasBackupContaining(tempDir, "invalid"));
        Assert.assertTrue("existing YAML path must not read or retire cfg", cfg.isFile());
    }

    @Test
    public void emptyYamlUsesSchemaDefaultsAndLeavesLegacyCfgUntouched() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), new byte[0]);
        File cfg = writeLegacyCfg(tempDir, 99, 2.0D, 6.0D, 12);
        byte[] cfgBefore = Files.readAllBytes(cfg.toPath());

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertEquals(0L, yaml.length());
        Assert.assertTrue(cfg.isFile());
        Assert.assertArrayEquals(cfgBefore, Files.readAllBytes(cfg.toPath()));
    }

    @Test
    public void secondBootstrapSamePathIsIdempotent() {
        ConfigManager first = ConfigBootstrap.bootstrap(tempDir, null);
        ConfigManager second = ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertSame(first, second);
    }

    @Test
    public void secondBootstrapDifferentPathFailsFast() {
        ConfigBootstrap.bootstrap(tempDir, null);
        File other = createCaseDir("other-bootstrap");

        try {
            ConfigBootstrap.bootstrap(other, null);
            Assert.fail("different-path bootstrap must fail fast");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("already initialized"));
        }
    }

    @Test
    public void requiredBackupNeverOverwritesExisting() throws Exception {
        File source = new File(tempDir, "sample.yaml");
        byte[] firstContent = "a: 1\n".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "a: 2\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source.toPath(), firstContent);

        File firstBackup = ConfigBootstrap.requiredBackup(source, "t");
        Files.write(source.toPath(), secondContent);
        File secondBackup = ConfigBootstrap.requiredBackup(source, "t");

        Assert.assertTrue(firstBackup.isFile());
        Assert.assertTrue(secondBackup.isFile());
        Assert.assertFalse(firstBackup.getName().equals(secondBackup.getName()));
        Assert.assertArrayEquals(firstContent, Files.readAllBytes(firstBackup.toPath()));
        Assert.assertArrayEquals(secondContent, Files.readAllBytes(secondBackup.toPath()));
    }

    @Test
    public void semanticInvalidExistingYamlBacksUpAndRestoresDefaults() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        String body = ""
                + "general:\n"
                + "  greeting: Bad\n"
                + "  chainRadius: 12.6\n"
                + "  tickBudgetMs: 15\n";
        Files.write(yaml.toPath(), body.getBytes(StandardCharsets.UTF_8));

        ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertTrue(hasBackupContaining(tempDir, "invalid"));
    }

    @Test
    public void rawTypeInvalidExistingYamlBacksUpAndRestoresDefaults() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "general:\n  chainRadius: '42'\n".getBytes(StandardCharsets.UTF_8));

        ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertTrue(hasBackupContaining(tempDir, "invalid"));
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
    public void legacyCfgDurationMapsAndClampsWithoutDroppingOtherValues() throws Exception {
        int[][] cases = {
                {12, 12},
                {100, 40}
        };

        for (int index = 0; index < cases.length; index++) {
            File caseDir = createCaseDir("cfg-" + index);
            File cfg = writeLegacyCfg(caseDir, 33 + index, 2.0D, 6.0D, cases[index][0]);

            resetBootstrapState();
            ConfigManager manager = ConfigBootstrap.bootstrap(caseDir, cfg);

            Assert.assertNotNull(manager);
            Assert.assertEquals(33 + index, Config.chainRadius);
            Assert.assertEquals(cases[index][1], Config.tickBudgetMs);
            Assert.assertFalse(cfg.exists());
            Assert.assertTrue(RawYamlPreflight.validate(ConfigBootstrap.yamlFile(), manager.schema()).isValid());
            Assert.assertTrue(ConfigSemanticValidator.captureAndValidate(manager).isValid());
        }
    }

    @Test
    public void invalidLegacyCfgMigrationCleansPartialPathAndRestoresDefaults() throws Exception {
        File cfg = writeLegacyCfg(tempDir, 33, 5.0D, 5.0D, 12);

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(manager);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertFalse(cfg.exists());
        Assert.assertTrue(ConfigSemanticValidator.captureAndValidate(manager).isValid());
    }

    @Test
    public void successfulMigrationRetireFailurePublishesNothingAndRetryUsesYamlAuthority() throws Exception {
        File cfg = writeLegacyCfg(tempDir, 33, 2.0D, 6.0D, 12);
        byte[] cfgBefore = Files.readAllBytes(cfg.toPath());
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        forceCfgRetireFailure();

        assertBootstrapRetireFailure(cfg, before);
        Assert.assertArrayEquals(cfgBefore, Files.readAllBytes(cfg.toPath()));
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Assert.assertTrue("migrated YAML should remain for retry", yaml.isFile() && yaml.length() > 0);
        Assert.assertTrue(readUtf8(yaml).contains("tickBudgetMs: 12"));

        ConfigBootstrap.setCfgRetirerForTests(null);
        ConfigManager retried = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(retried);
        Assert.assertSame(retried, ConfigBootstrap.manager());
        Assert.assertEquals("retry must load migrated YAML instead of cached half-init/default cfg path",
                33, Config.chainRadius);
        Assert.assertEquals(12, Config.tickBudgetMs);
        Assert.assertTrue("authoritative YAML retry must ignore remaining cfg", cfg.isFile());
    }

    @Test
    public void failedMigrationRecoveryRetireFailurePublishesNothing() throws Exception {
        File cfg = writeLegacyCfg(tempDir, 33, 5.0D, 5.0D, 12);
        byte[] cfgBefore = Files.readAllBytes(cfg.toPath());
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        forceCfgRetireFailure();

        assertBootstrapRetireFailure(cfg, before);
        Assert.assertArrayEquals(cfgBefore, Files.readAllBytes(cfg.toPath()));
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Assert.assertTrue("default recovery YAML should exist for a later authoritative retry",
                yaml.isFile() && yaml.length() > 0);

        ConfigBootstrap.setCfgRetirerForTests(null);
        ConfigManager retried = ConfigBootstrap.bootstrap(tempDir, cfg);

        Assert.assertNotNull(retried);
        Assert.assertSame(retried, ConfigBootstrap.manager());
        Assert.assertEquals("retry must load recovered default YAML as authority",
                QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.TICK_BUDGET_MS, Config.tickBudgetMs);
        Assert.assertTrue("authoritative retry must leave failed-import cfg untouched", cfg.isFile());
    }

    @Test
    public void epochOverflowBeforeApplyAllPublishesNothing() {
        ConfigBootstrap.bootstrap(tempDir, null);
        long epochBefore = ConfigBootstrap.currentCommittedSnapshot().epoch;
        ConfigBootstrap.resetForTests();
        setStaticSentinels();
        Object[] before = captureRuntimeConfigValues();
        ConfigBootstrap.setCommitEpochForTests(Long.MAX_VALUE);

        try {
            try {
                ConfigBootstrap.bootstrap(tempDir, null);
                Assert.fail("epoch overflow must fail before any publication");
            } catch (ConfigAuthorityInvariantError expected) {
                Assert.assertTrue(expected.getMessage().contains("epoch overflow"));
            }

            assertBootstrapStateUnpublished(before);
        } finally {
            ConfigBootstrap.resetForTests();
            ConfigBootstrap.setCommitEpochForTests(Math.max(epochBefore, 1_000_000_000L));
        }
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
    public void cleanupPartialYamlBacksUpBeforeDefaultRebuild() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "general:\n  chainRadius: 'partial'\n".getBytes(StandardCharsets.UTF_8));

        ConfigBootstrap.cleanupPartialYamlIfAny(yaml);

        Assert.assertFalse(yaml.exists());
        Assert.assertTrue(hasBackupContaining(tempDir, "partial"));
        ConfigBootstrap.StrictLoad defaults = ConfigBootstrap.persistDefaultsStrict(
                yaml, QzMinerConfigSchema.create(), "migration test recovery");
        Assert.assertTrue(defaults.isValid());
        Assert.assertTrue(RawYamlPreflight.validate(yaml, defaults.manager.schema()).isValid());
    }

    private File createCaseDir(String name) {
        File directory = new File(tempDir, name);
        Assert.assertTrue("case directory should be created", directory.mkdirs());
        return directory;
    }

    private static File writeLegacyCfg(
            File directory, int radius, double fadeStart, double fadeEnd, int duration) throws Exception {
        File cfg = new File(directory, "qz_miner.cfg");
        String body = "general {\n"
                + "  I:chainRadius=" + radius + "\n"
                + "  I:parallelTickMinDurationMs=" + duration + "\n"
                + "}\n"
                + "client {\n"
                + "  D:clientPreviewAlphaFadeStartRadius=" + fadeStart + "\n"
                + "  D:clientPreviewAlphaFadeEndRadius=" + fadeEnd + "\n"
                + "}\n";
        Files.write(cfg.toPath(), body.getBytes(StandardCharsets.UTF_8));
        return cfg;
    }

    private static String readUtf8(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void assertLegacyBudgetKeysRemoved(String yaml) {
        for (String key : LEGACY_BUDGET_KEYS) {
            Assert.assertFalse(key + " should be removed", yaml.contains(key));
        }
    }

    private static boolean hasBackupContaining(File directory, String marker) {
        File[] children = directory.listFiles();
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

    private static void resetBootstrapState() {
        ConfigBootstrap.resetForTests();
        resetStaticDefaults();
    }

    private static void forceCfgRetireFailure() {
        ConfigBootstrap.setCfgRetirerForTests(new ConfigBootstrap.CfgRetirer() {
            @Override
            public void move(File source, File target) throws IOException {
                throw new IOException("forced cfg retire failure");
            }
        });
    }

    private static void assertBootstrapRetireFailure(File cfg, Object[] before) {
        try {
            ConfigBootstrap.bootstrap(cfg.getParentFile(), cfg);
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
        Assert.assertArrayEquals("all Config runtime fields must remain untouched", before,
                captureRuntimeConfigValues());
    }

    private static void setStaticSentinels() {
        Config.greeting = "sentinel";
        Config.chainRadius = 901;
        Config.chainMaxBlocks = 902;
        Config.chainLoggingShellLayers = 903;
        Config.cableReplaceMaxPerTick = 905;
        Config.chainWatchdogTimeoutTicks = 906;
        Config.tickBudgetMs = 907;
        Config.enableUnlimitedOreFortune = true;
        Config.enableFortuneForPlacedOre = true;
        Config.clientEnablePreviewRender = false;
        Config.tunnelDirectionSource = TunnelDirectionSource.HIT_FACE;
        Config.autoToolSwapEnabled = false;
        Config.autoToolPrioritySelectors = java.util.Collections.singletonList(
                club.heiqi.qz_miner.toolswap.ToolSelectorParser.parse("sentinel:item@*"));
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
                Integer.valueOf(Config.cableReplaceMaxPerTick),
                Integer.valueOf(Config.chainWatchdogTimeoutTicks),
                Integer.valueOf(Config.tickBudgetMs),
                Boolean.valueOf(Config.enableUnlimitedOreFortune),
                Boolean.valueOf(Config.enableFortuneForPlacedOre),
                Boolean.valueOf(Config.clientEnablePreviewRender),
                Config.tunnelDirectionSource,
                Boolean.valueOf(Config.autoToolSwapEnabled),
                Config.autoToolPrioritySelectors,
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
        Config.cableReplaceMaxPerTick = QzMinerConfigDefaults.CABLE_REPLACE_MAX_PER_TICK;
        Config.chainWatchdogTimeoutTicks = QzMinerConfigDefaults.CHAIN_WATCHDOG_TIMEOUT_TICKS;
        Config.tickBudgetMs = QzMinerConfigDefaults.TICK_BUDGET_MS;
        Config.enableUnlimitedOreFortune = QzMinerConfigDefaults.ENABLE_UNLIMITED_ORE_FORTUNE;
        Config.enableFortuneForPlacedOre = QzMinerConfigDefaults.ENABLE_FORTUNE_FOR_PLACED_ORE;
        Config.clientEnablePreviewRender = QzMinerConfigDefaults.CLIENT_ENABLE_PREVIEW_RENDER;
        Config.tunnelDirectionSource = TunnelDirectionSource.legacyDefault();
        Config.autoToolSwapEnabled = QzMinerConfigDefaults.CLIENT_AUTO_TOOL_SWAP_ENABLED;
        Config.autoToolPrioritySelectors = java.util.Collections.emptyList();
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
