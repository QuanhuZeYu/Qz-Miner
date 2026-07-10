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
 * 启动加载：默认 YAML、坏 YAML 恢复、无 cfg 默认路径（纯 JVM + 临时目录）。
 *
 * <p>Legacy cfg 成功导入依赖 Forge {@code Configuration}，在纯 JVM 下通常不可用；
 * 该路径留给实机 / 集成验证。本测试覆盖 YAML 权威与恢复语义。</p>
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
        Assert.assertEquals(8, Config.chainRadius);
        Assert.assertEquals("Hello World", Config.greeting);
        Assert.assertTrue(Config.clientEnablePreviewRender);
        Assert.assertSame(manager, ConfigBootstrap.manager());
    }

    @Test
    public void existingValidYamlBootstrapsWithoutDefaultsOverwrite() throws Exception {
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
    public void corruptYamlIsBackedUpAndDefaultsRestored() throws Exception {
        File yaml = new File(tempDir, ConfigBootstrap.YAML_FILE_NAME);
        Files.write(yaml.toPath(), "this: [is: not: valid: yaml:::".getBytes(StandardCharsets.UTF_8));

        ConfigManager manager = ConfigBootstrap.bootstrap(tempDir, null);

        Assert.assertNotNull(manager);
        Assert.assertEquals(8, Config.chainRadius);
        Assert.assertEquals("Hello World", Config.greeting);
        // 原坏文件应被备份；权威 YAML 应重新可解析
        Assert.assertTrue(yaml.isFile());
        boolean foundBackup = false;
        File[] children = tempDir.listFiles();
        Assert.assertNotNull(children);
        for (File child : children) {
            if (child.getName().contains("corrupt") && child.getName().endsWith(".bak")) {
                foundBackup = true;
                break;
            }
        }
        Assert.assertTrue("corrupt yaml backup expected", foundBackup);
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
