package club.heiqi.qz_miner.config;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.config.ConfigChangeEvent;
import club.heiqi.config.ConfigChangeListener;
import club.heiqi.config.runtime.ConfigManager;
import cpw.mods.fml.relauncher.FMLInjectionData;

/** 服务端配置白名单、严格解析与提交令牌语义。 */
public class ServerConfigMutationServiceTest {

    private File tempDir;
    private Field minecraftHomeField;
    private Object previousMinecraftHome;
    private ConfigManager manager;
    private ServerConfigMutationService service;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-server-config-").toFile();
        minecraftHomeField = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHomeField.setAccessible(true);
        previousMinecraftHome = minecraftHomeField.get(null);
        minecraftHomeField.set(null, tempDir);
        ConfigBootstrap.resetForTests();
        manager = ConfigBootstrap.bootstrap(tempDir, null);
        service = new ServerConfigMutationService(manager);
    }

    @After
    public void tearDown() throws Exception {
        ConfigBootstrap.resetForTests();
        minecraftHomeField.set(null, previousMinecraftHome);
        deleteRecursively(tempDir);
    }

    @Test
    public void listAndGetExposeOnlyExplicitGeneralScalarWhitelist() {
        List<String> all = service.list("").lines();
        Assert.assertEquals(11, all.size());
        Assert.assertTrue(all.get(0).startsWith("general.greeting = "));
        for (String line : all) {
            Assert.assertFalse(line.startsWith("client."));
        }
        Assert.assertEquals(2, service.list("general.enable").lines().size());
        Assert.assertFalse(service.get("client.clientEnablePreviewRender").isSuccess());
        Assert.assertFalse(service.get("general.unknown").isSuccess());
    }

    @Test
    public void setUsesStrictTypesAndFallsBackToSingleCaptureWithoutListener() {
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();

        ServerConfigMutationService.Result result = service.set("general.chainRadius", "23");

        Assert.assertTrue(result.lines().toString(), result.isSuccess());
        Assert.assertEquals(before.epoch + 1L, result.committed().epoch);
        Assert.assertSame(result.committed(), ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(Double.valueOf(23.0D), manager.authority().get("general.chainRadius"));
    }

    @Test
    public void successfulSaveReusesListenerCapturedCurrentWithoutExtraEpoch() {
        final AtomicInteger events = new AtomicInteger();
        manager.eventBus().subscribe(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                events.incrementAndGet();
                ConfigBootstrap.captureCommittedSnapshot(manager);
            }
        });
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();

        ServerConfigMutationService.Result result = service.set("general.greeting", "Hello server operators");

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(1, events.get());
        Assert.assertEquals(before.epoch + 1L, result.committed().epoch);
        Assert.assertEquals("Hello server operators", manager.authority().get("general.greeting"));
    }

    @Test
    public void parseOrValidationFailureDoesNotCommitOrReturnHotApplyToken() throws Exception {
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();
        byte[] yamlBefore = Files.readAllBytes(ConfigBootstrap.yamlFile().toPath());

        assertFailed(service.set("general.enableUnlimitedOreFortune", "TRUE"));
        assertFailed(service.set("general.chainRadius", "NaN"));
        assertFailed(service.set("general.chainRadius", "Infinity"));
        assertFailed(service.set("general.chainRadius", "0"));
        assertFailed(service.set("client.clientEnablePreviewRender", "false"));

        Assert.assertSame(before, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertArrayEquals(yamlBefore, Files.readAllBytes(ConfigBootstrap.yamlFile().toPath()));
    }

    @Test
    public void strictReloadFailureLeavesAuthorityAndCurrentUntouched() throws Exception {
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();
        Object radiusBefore = manager.authority().get("general.chainRadius");
        Files.write(ConfigBootstrap.yamlFile().toPath(),
                "general:\n  chainRadius: '42'\n".getBytes(StandardCharsets.UTF_8));

        ServerConfigMutationService.Result result = service.reload();

        assertFailed(result);
        Assert.assertSame(before, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(radiusBefore, manager.authority().get("general.chainRadius"));
    }

    @Test
    public void successfulStrictReloadReusesListenerCapture() throws Exception {
        manager.eventBus().subscribe(new ConfigChangeListener() {
            @Override
            public void onConfigChanged(ConfigChangeEvent event) {
                ConfigBootstrap.captureCommittedSnapshot(manager);
            }
        });
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();
        Files.write(ConfigBootstrap.yamlFile().toPath(),
                "general:\n  chainRadius: 31\n".getBytes(StandardCharsets.UTF_8));

        ServerConfigMutationService.Result result = service.reload();

        Assert.assertTrue(result.lines().toString(), result.isSuccess());
        Assert.assertEquals(before.epoch + 1L, result.committed().epoch);
        Assert.assertEquals(Double.valueOf(31.0D), manager.authority().get("general.chainRadius"));
    }

    private static void assertFailed(ServerConfigMutationService.Result result) {
        Assert.assertFalse(result.lines().toString(), result.isSuccess());
        Assert.assertNull(result.committed());
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
