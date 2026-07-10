package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.qz_miner.Config;

/**
 * Bootstrap 磁盘事务 / 幂等 / 坏 YAML 恢复（纯 JVM）。
 */
public class ConfigBootstrapTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-bootstrap-").toFile();
        ConfigBootstrap.resetForTests();
        resetStaticDefaults();
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        resetStaticDefaults();
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
        Assert.assertSame(manager, ConfigBootstrap.manager());
        Assert.assertNotNull(ConfigBootstrap.lastValidSnapshot());
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
        Assert.assertEquals(8, Config.clientPreviewMaxRadius);
        Assert.assertTrue(Config.enableUnlimitedOreFortune);
    }

    @Test
    public void corruptYamlIsBackedUpWithUniqueNameAndDefaultsRestored() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "this: [is: not: valid: yaml:::".getBytes(StandardCharsets.UTF_8));

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertNotNull(manager);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertEquals(QzMinerConfigDefaults.GREETING, Config.greeting);
        Assert.assertTrue(yaml.isFile() && yaml.length() > 0);

        int backups = 0;
        File[] children = tempDir.listFiles();
        Assert.assertNotNull(children);
        for (File child : children) {
            if (child.getName().contains("corrupt") && child.getName().endsWith(".bak")) {
                backups++;
            }
        }
        Assert.assertTrue("corrupt yaml unique backup expected", backups >= 1);
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

    @Test(expected = IllegalStateException.class)
    public void semanticInvalidExistingYamlFailsAtBootstrap() throws Exception {
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
