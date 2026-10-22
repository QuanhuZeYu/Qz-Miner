package club.heiqi.qz_miner.config;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import club.heiqi.uilib.ui.config.ConfigCodecs;
import club.heiqi.uilib.ui.config.ConfigKey;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import cpw.mods.fml.relauncher.FMLInjectionData;

/** 服务端配置白名单、严格解析与提交令牌语义。 */
public class ServerConfigMutationServiceTest {

    private File tempDir;
    private Field minecraftHomeField;
    private Object previousMinecraftHome;
    private ServerConfigMutationService service;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-server-config-").toFile();
        minecraftHomeField = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHomeField.setAccessible(true);
        previousMinecraftHome = minecraftHomeField.get(null);
        minecraftHomeField.set(null, tempDir);
        ConfigTestSupport.reset();
        ConfigBootstrap.bootstrap(tempDir, null);
        service = ServerConfigMutationService.fromBootstrap();
    }

    @After
    public void tearDown() throws Exception {
        ConfigTestSupport.reset();
        minecraftHomeField.set(null, previousMinecraftHome);
        deleteRecursively(tempDir);
    }

    @Test
    public void listAndGetExposeOnlyExplicitGeneralScalarWhitelist() {
        List<String> all = service.list("").lines();
        Assert.assertFalse("白名单不得为空", all.isEmpty());
        Assert.assertTrue(all.get(0).startsWith("general.greeting = "));
        Assert.assertTrue(all.toString(), all.toString().contains("general.tickBudgetMs = "));
        // 白名单内容按行为判定（不再冻结条数）：每条必须是 schema 中真实存在的 general.* scalar 字段，
        // 新增可热改键时本用例不需要跟着改；「恰好 N 条」会随每次正常增补而红。
        for (String line : all) {
            String path = line.substring(0, line.indexOf(" = "));
            Assert.assertTrue("白名单只能列 general 段: " + path, path.startsWith("general."));
            ConfigKey<?> key = QzMinerConfigSchema.key(path);
            Assert.assertTrue("白名单只能列 scalar 字段: " + path,
                    key.codec() == ConfigCodecs.STRING || key.codec() == ConfigCodecs.DOUBLE
                            || key.codec() == ConfigCodecs.BOOLEAN);
        }
        Assert.assertTrue("白名单必须覆盖 issue #242 的饥饿值消耗键",
                all.toString().contains("general.harvestExhaustionPerBlock = "));
        Assert.assertEquals(2, service.list("general.enable").lines().size());
        Assert.assertFalse(service.get("client.clientEnablePreviewRender").isSuccess());
        Assert.assertFalse(service.get("general.unknown").isSuccess());
    }

    @Test
    public void setUsesStrictTypesAndReturnsOneCommittedToken() {
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();

        ServerConfigMutationService.Result result = service.set("general.chainRadius", "23");

        Assert.assertTrue(result.lines().toString(), result.isSuccess());
        Assert.assertEquals(before.epoch + 1L, result.committed().epoch);
        Assert.assertSame(result.committed(), ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(Double.valueOf(23.0D), ConfigBootstrap.document().get(QzMinerConfigSchema.key("general.chainRadius")));
    }

    @Test
    public void successfulSaveNotifiesOnceWithoutExtraEpoch() {
        final AtomicInteger events = new AtomicInteger();
        ConfigBootstrap.subscribe(committed -> events.incrementAndGet());
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();

        ServerConfigMutationService.Result result = service.set("general.greeting", "Hello server operators");

        Assert.assertTrue(result.isSuccess());
        Assert.assertEquals(1, events.get());
        Assert.assertEquals(before.epoch + 1L, result.committed().epoch);
        Assert.assertEquals("Hello server operators", ConfigBootstrap.document().get(QzMinerConfigSchema.key("general.greeting")));
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
        Object radiusBefore = ConfigBootstrap.document().get(QzMinerConfigSchema.key("general.chainRadius"));
        Files.write(ConfigBootstrap.yamlFile().toPath(),
                "general:\n  chainRadius: '42'\n".getBytes(StandardCharsets.UTF_8));

        ServerConfigMutationService.Result result = service.reload();

        assertFailed(result);
        Assert.assertSame(before, ConfigBootstrap.currentCommittedSnapshot());
        Assert.assertEquals(radiusBefore, ConfigBootstrap.document().get(QzMinerConfigSchema.key("general.chainRadius")));
    }

    @Test
    public void successfulStrictReloadNotifiesOnce() throws Exception {
        final AtomicInteger events = new AtomicInteger();
        ConfigBootstrap.subscribe(committed -> events.incrementAndGet());
        CommittedSnapshot before = ConfigBootstrap.currentCommittedSnapshot();
        Files.write(ConfigBootstrap.yamlFile().toPath(),
                "general:\n  chainRadius: 31\n".getBytes(StandardCharsets.UTF_8));

        ServerConfigMutationService.Result result = service.reload();

        Assert.assertTrue(result.lines().toString(), result.isSuccess());
        Assert.assertEquals(before.epoch + 1L, result.committed().epoch);
        Assert.assertEquals(1, events.get());
        Assert.assertEquals(Double.valueOf(31.0D), ConfigBootstrap.document().get(QzMinerConfigSchema.key("general.chainRadius")));
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
