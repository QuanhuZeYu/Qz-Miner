package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.runtime.ConfigManager;
import club.heiqi.config.runtime.DraftBuffer;
import club.heiqi.config.schema.ConfigSchema;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;

/**
 * Authority → 静态字段：全 21 字段 + 非法值不 round。
 */
public class ConfigValueBridgeTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-bridge-").toFile();
        resetStaticDefaults();
        ConfigBootstrap.resetForTests();
    }

    @After
    public void tearDown() {
        ConfigBootstrap.resetForTests();
        resetStaticDefaults();
        deleteRecursively(tempDir);
    }

    @Test
    public void applyAllMapsAllTwentyOneFields() throws Exception {
        File yaml = new File(tempDir, "qz_miner.yaml");
        ConfigSchema schema = QzMinerConfigSchema.create();
        ConfigManager manager = ConfigManager.bootstrap(yaml, schema, ConfigSemanticValidator.draftValidator());
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.greeting", "HiAll");
        draft.setDraft("general.chainRadius", Double.valueOf(32.0));
        draft.setDraft("general.chainMaxBlocks", Double.valueOf(2048.0));
        draft.setDraft("general.chainLoggingShellLayers", Double.valueOf(3.0));
        draft.setDraft("general.maxBreakPerTick", Double.valueOf(16.0));
        draft.setDraft("general.cableReplaceMaxPerTick", Double.valueOf(512.0));
        draft.setDraft("general.chainWatchdogTimeoutTicks", Double.valueOf(40.0));
        draft.setDraft("general.parallelTickMinDurationMs", Double.valueOf(12.0));
        draft.setDraft("general.parallelTickServerWorkBudgetUnits", Double.valueOf(320.0));
        draft.setDraft("general.enableUnlimitedOreFortune", Boolean.TRUE);
        draft.setDraft("general.enableFortuneForPlacedOre", Boolean.TRUE);
        draft.setDraft("client.clientEnablePreviewRender", Boolean.FALSE);
        draft.setDraft("client.autoToolSwapEnabled", Boolean.FALSE);
        draft.setDraft("client.autoToolPrioritySelectors",
                java.util.Arrays.asList(" ore:toolPickaxe ", "mod:drill@4"));
        draft.setDraft("client.parallelTickClientWorkBudgetUnits", Double.valueOf(200.0));
        draft.setDraft("client.clientPreviewMaxRadius", Double.valueOf(8.0));
        draft.setDraft("client.clientPreviewMaxTargets", Double.valueOf(128.0));
        draft.setDraft("client.clientPreviewAlphaFadeStartRadius", Double.valueOf(1.5));
        draft.setDraft("client.clientPreviewAlphaFadeEndRadius", Double.valueOf(4.0));
        draft.setDraft("client.clientPreviewAlphaStartValue", Double.valueOf(0.6));
        draft.setDraft("client.clientPreviewAlphaEndValue", Double.valueOf(0.1));
        Assert.assertTrue(manager.save(draft).isSuccess());

        ConfigSemanticValidator.ParseOutcome outcome = ConfigSemanticValidator.captureAndValidate(manager);
        Assert.assertTrue(outcome.result.summary(), outcome.isValid());
        ValidatedSnapshot snap = outcome.snapshot;
        ConfigValueBridge.applyAll(snap);

        Assert.assertEquals("HiAll", Config.greeting);
        Assert.assertEquals(32, Config.chainRadius);
        Assert.assertEquals(2048, Config.chainMaxBlocks);
        Assert.assertEquals(3, Config.chainLoggingShellLayers);
        Assert.assertEquals(16, Config.maxBreakPerTick);
        Assert.assertEquals(512, Config.cableReplaceMaxPerTick);
        Assert.assertEquals(40, Config.chainWatchdogTimeoutTicks);
        Assert.assertEquals(12, Config.parallelTickMinDurationMs);
        Assert.assertEquals(320, Config.parallelTickServerWorkBudgetUnits);
        Assert.assertTrue(Config.enableUnlimitedOreFortune);
        Assert.assertTrue(Config.enableFortuneForPlacedOre);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertFalse(Config.autoToolSwapEnabled);
        Assert.assertEquals("ore:toolPickaxe", Config.autoToolPrioritySelectors.get(0).canonicalText());
        Assert.assertEquals("mod:drill@4", Config.autoToolPrioritySelectors.get(1).canonicalText());
        try {
            Config.autoToolPrioritySelectors.add(
                    club.heiqi.qz_miner.toolswap.ToolSelectorParser.parse("mod:other@*"));
            Assert.fail("runtime selector list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // 合同断言
        }
        Assert.assertEquals(200, Config.parallelTickClientWorkBudgetUnits);
        Assert.assertEquals(8, Config.clientPreviewMaxRadius);
        Assert.assertEquals(128, Config.clientPreviewMaxTargets);
        Assert.assertEquals(1.5D, Config.clientPreviewAlphaFadeStartRadius, 1e-9);
        Assert.assertEquals(4.0D, Config.clientPreviewAlphaFadeEndRadius, 1e-9);
        Assert.assertEquals(0.6D, Config.clientPreviewAlphaStartValue, 1e-9);
        Assert.assertEquals(0.1D, Config.clientPreviewAlphaEndValue, 1e-9);
    }

    @Test
    public void applyGeneralDoesNotTouchClient() {
        ValidatedSnapshot defaults = defaultsSnapshot();
        Config.clientEnablePreviewRender = false;
        Config.clientPreviewMaxRadius = 1;
        ConfigValueBridge.applyGeneralFromSnapshot(defaults);
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        Assert.assertFalse("client must not be overwritten by applyGeneral", Config.clientEnablePreviewRender);
        Assert.assertEquals(1, Config.clientPreviewMaxRadius);
    }

    @Test
    public void nonIntegerDoesNotRoundViaValidator() throws Exception {
        File yaml = new File(tempDir, "bad.yaml");
        ConfigManager manager = ConfigManager.bootstrap(
                yaml, QzMinerConfigSchema.create(), ConfigSemanticValidator.draftValidator());
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.chainRadius", Double.valueOf(12.6));
        Assert.assertEquals(club.heiqi.config.runtime.SaveOutcome.Status.INVALID, manager.save(draft).status());
        // 静态字段保持默认，未应用非法值
        Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
    }

    private ValidatedSnapshot defaultsSnapshot() {
        try {
            File yaml = new File(tempDir, "def.yaml");
            ConfigManager manager = ConfigManager.bootstrap(
                    yaml, QzMinerConfigSchema.create(), ConfigSemanticValidator.draftValidator());
            return ConfigSemanticValidator.captureAndValidate(manager).snapshot;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
