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

/**
 * Authority → 静态字段回灌与 NUMBER→int / alpha 约束测试。
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
    public void applyFromAuthorityMapsAllFieldsAndRoundsInts() throws Exception {
        File yaml = new File(tempDir, "qz_miner.yaml");
        ConfigSchema schema = QzMinerConfigSchema.create();
        ConfigManager manager = ConfigManager.bootstrap(yaml, schema);
        DraftBuffer draft = manager.openDraft();
        draft.setDraft("general.greeting", "Hi");
        draft.setDraft("general.chainRadius", Double.valueOf(12.6));
        draft.setDraft("general.chainMaxBlocks", Double.valueOf(2048.4));
        draft.setDraft("client.clientPreviewAlphaStartValue", Double.valueOf(0.9));
        draft.setDraft("client.clientPreviewAlphaEndValue", Double.valueOf(0.2));
        draft.setDraft("client.clientPreviewAlphaFadeStartRadius", Double.valueOf(1.0));
        draft.setDraft("client.clientPreviewAlphaFadeEndRadius", Double.valueOf(0.5)); // 将夹到 start+0.001
        Assert.assertTrue(manager.save(draft).isSuccess());

        ConfigValueBridge.applyFromAuthority(manager.authority());

        Assert.assertEquals("Hi", Config.greeting);
        Assert.assertEquals(13, Config.chainRadius); // Math.round(12.6)
        Assert.assertEquals(2048, Config.chainMaxBlocks);
        Assert.assertEquals(0.9D, Config.clientPreviewAlphaStartValue, 1e-9);
        Assert.assertEquals(0.2D, Config.clientPreviewAlphaEndValue, 1e-9);
        Assert.assertEquals(1.0D, Config.clientPreviewAlphaFadeStartRadius, 1e-9);
        Assert.assertTrue(Config.clientPreviewAlphaFadeEndRadius >= Config.clientPreviewAlphaFadeStartRadius + 0.001D);
    }

    @Test
    public void toIntClampsToMin() {
        Assert.assertEquals(1, ConfigValueBridge.toInt(0.2, 1));
        Assert.assertEquals(10, ConfigValueBridge.toInt(9.6, 1));
    }

    @Test
    public void alphaEndCannotExceedStart() {
        Config.clientPreviewAlphaStartValue = 0.4D;
        Config.clientPreviewAlphaEndValue = 0.9D;
        Config.clientPreviewAlphaFadeStartRadius = -1.0D;
        Config.clientPreviewAlphaFadeEndRadius = 0.0D;
        ConfigValueBridge.applyAlphaConstraints();
        Assert.assertEquals(0.0D, Config.clientPreviewAlphaFadeStartRadius, 1e-9);
        Assert.assertTrue(Config.clientPreviewAlphaFadeEndRadius >= 0.001D);
        Assert.assertEquals(0.4D, Config.clientPreviewAlphaStartValue, 1e-9);
        Assert.assertEquals(0.4D, Config.clientPreviewAlphaEndValue, 1e-9);
    }

    private static void resetStaticDefaults() {
        Config.greeting = "Hello World";
        Config.chainRadius = 8;
        Config.chainMaxBlocks = 1024;
        Config.chainLoggingShellLayers = 1;
        Config.maxBreakPerTick = 64;
        Config.cableReplaceMaxPerTick = 1024;
        Config.chainWatchdogTimeoutTicks = 50;
        Config.parallelTickMinDurationMs = 15;
        Config.parallelTickServerWorkBudgetUnits = 640;
        Config.enableUnlimitedOreFortune = false;
        Config.enableFortuneForPlacedOre = false;
        Config.clientEnablePreviewRender = true;
        Config.parallelTickClientWorkBudgetUnits = 640;
        Config.clientPreviewMaxRadius = 16;
        Config.clientPreviewMaxTargets = 1024;
        Config.clientPreviewAlphaFadeStartRadius = 2.0D;
        Config.clientPreviewAlphaFadeEndRadius = 6.0D;
        Config.clientPreviewAlphaStartValue = 0.78D;
        Config.clientPreviewAlphaEndValue = 0.15D;
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
