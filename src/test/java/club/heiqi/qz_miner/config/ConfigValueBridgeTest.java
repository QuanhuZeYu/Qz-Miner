package club.heiqi.qz_miner.config;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.LinkedHashMap;
import club.heiqi.uilib.ui.config.ConfigDocument;
import club.heiqi.uilib.ui.config.ConfigKey;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.config.ConfigSemanticValidator.ValidatedSnapshot;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;

/**
 * Authority → 静态字段：既有 19 个 runtime 字段 + 非法值不 round（本轮新增键由 PreviewConfigSurfaceTest 覆盖）。
 */
public class ConfigValueBridgeTest {

    private File tempDir;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-bridge-").toFile();
        resetStaticDefaults();
        ConfigTestSupport.reset();
    }

    @After
    public void tearDown() {
        ConfigTestSupport.reset();
        resetStaticDefaults();
        deleteRecursively(tempDir);
    }

    @Test
    public void applyAllMapsAllRuntimeFields() throws Exception {
        ConfigDocument document = ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA);
        Map<ConfigKey<?>, Object> draft = new LinkedHashMap<ConfigKey<?>, Object>();
        draft.put(QzMinerConfigSchema.key("general.greeting"), "HiAll");
        draft.put(QzMinerConfigSchema.key("general.chainRadius"), Double.valueOf(32.0));
        draft.put(QzMinerConfigSchema.key("general.chainMaxBlocks"), Double.valueOf(2048.0));
        draft.put(QzMinerConfigSchema.key("general.chainLoggingShellLayers"), Double.valueOf(3.0));
        draft.put(QzMinerConfigSchema.key("general.cableReplaceMaxPerTick"), Double.valueOf(512.0));
        draft.put(QzMinerConfigSchema.key("general.chainWatchdogTimeoutTicks"), Double.valueOf(40.0));
        draft.put(QzMinerConfigSchema.key("general.tickBudgetMs"), Double.valueOf(25.0));
        draft.put(QzMinerConfigSchema.key("general.enableUnlimitedOreFortune"), Boolean.TRUE);
        draft.put(QzMinerConfigSchema.key("general.enableFortuneForPlacedOre"), Boolean.TRUE);
        draft.put(QzMinerConfigSchema.key("client.clientEnablePreviewRender"), Boolean.FALSE);
        draft.put(QzMinerConfigSchema.key("client.tunnelDirectionSource"), "hit_face");
        draft.put(QzMinerConfigSchema.key("client.autoToolSwapEnabled"), Boolean.FALSE);
        draft.put(QzMinerConfigSchema.key("client.autoToolPrioritySelectors"),
                java.util.Arrays.asList(" ore:toolPickaxe ", "mod:drill@4"));
        draft.put(QzMinerConfigSchema.key("client.clientPreviewMaxRadius"), Double.valueOf(8.0));
        draft.put(QzMinerConfigSchema.key("client.clientPreviewMaxTargets"), Double.valueOf(128.0));
        draft.put(QzMinerConfigSchema.key("client.clientPreviewAlphaFadeStartRadius"), Double.valueOf(1.5));
        draft.put(QzMinerConfigSchema.key("client.clientPreviewAlphaFadeEndRadius"), Double.valueOf(4.0));
        draft.put(QzMinerConfigSchema.key("client.clientPreviewAlphaStartValue"), Double.valueOf(0.6));
        draft.put(QzMinerConfigSchema.key("client.clientPreviewAlphaEndValue"), Double.valueOf(0.1));
        document = document.withAll(draft);

        ConfigSemanticValidator.ParseOutcome outcome = ConfigSemanticValidator.validate(document);
        Assert.assertTrue(outcome.result.summary(), outcome.isValid());
        ValidatedSnapshot snap = outcome.snapshot;
        ConfigValueBridge.applyAll(snap);

        Assert.assertEquals("HiAll", Config.greeting);
        Assert.assertEquals(32, Config.chainRadius);
        Assert.assertEquals(2048, Config.chainMaxBlocks);
        Assert.assertEquals(3, Config.chainLoggingShellLayers);
        Assert.assertEquals(512, Config.cableReplaceMaxPerTick);
        Assert.assertEquals(40, Config.chainWatchdogTimeoutTicks);
        Assert.assertEquals(25, Config.tickBudgetMs);
        Assert.assertTrue(Config.enableUnlimitedOreFortune);
        Assert.assertTrue(Config.enableFortuneForPlacedOre);
        Assert.assertFalse(Config.clientEnablePreviewRender);
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE, Config.tunnelDirectionSource);
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
    public void nonIntegerDoesNotRoundViaDocumentValidation() {
        try {
            ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA).withAll(
                    ConfigTestSupport.changes("general.chainRadius", 12.6));
            Assert.fail("小数半径不能 round 后发布");
        } catch (RuntimeException expected) {
            Assert.assertEquals(QzMinerConfigDefaults.CHAIN_RADIUS, Config.chainRadius);
        }
    }

    private ValidatedSnapshot defaultsSnapshot() {
        return ConfigSemanticValidator.validate(ConfigDocument.defaults(QzMinerConfigSchema.SCHEMA)).snapshot;
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
