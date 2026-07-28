package club.heiqi.qz_miner.config;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.Config;
import cpw.mods.fml.relauncher.FMLInjectionData;

/** 服务端 general 热发布顺序与 epoch 幂等语义。 */
public class ServerConfigHotApplyServiceTest {

    private File tempDir;
    private Field minecraftHomeField;
    private Object previousMinecraftHome;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("qz-miner-hot-apply-").toFile();
        minecraftHomeField = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHomeField.setAccessible(true);
        previousMinecraftHome = minecraftHomeField.get(null);
        minecraftHomeField.set(null, tempDir);
        ConfigBootstrap.resetForTests();
        ConfigBootstrap.bootstrap(tempDir, null);
    }

    @After
    public void tearDown() throws Exception {
        ConfigBootstrap.resetForTests();
        minecraftHomeField.set(null, previousMinecraftHome);
        deleteRecursively(tempDir);
    }

    @Test
    public void appliesGeneralBeforeCallbacksAndIgnoresDuplicateEpoch() {
        ServerConfigMutationService.Result mutation = ServerConfigMutationService.fromBootstrap()
                .set("general.chainRadius", "37");
        Assert.assertTrue(mutation.isSuccess());
        final List<String> order = new ArrayList<String>();
        ServerConfigHotApplyService service = new ServerConfigHotApplyService(
                new ServerConfigHotApplyService.Callbacks() {
                    @Override
                    public void publishPolicy(CommittedSnapshot committed) {
                        Assert.assertEquals(37, Config.chainRadius);
                        order.add("policy");
                    }

                    @Override
                    public void revalidateOnlineAccepted(CommittedSnapshot committed) {
                        Assert.assertEquals(37, Config.chainRadius);
                        order.add("accepted");
                    }
                });

        Assert.assertTrue(service.apply(mutation.committed()));
        Assert.assertFalse(service.apply(mutation.committed()));

        Assert.assertEquals(java.util.Arrays.asList("policy", "accepted"), order);
        Assert.assertEquals(mutation.committed().epoch, service.appliedEpoch());
    }

    @Test
    public void callbackFailureStillAttemptsLaterStagesAndLeavesEpochRetryable() {
        ServerConfigMutationService.Result mutation = ServerConfigMutationService.fromBootstrap()
                .set("general.chainRadius", "41");
        Assert.assertTrue(mutation.isSuccess());
        final AtomicInteger policyCalls = new AtomicInteger();
        final AtomicInteger acceptedCalls = new AtomicInteger();
        ServerConfigHotApplyService service = new ServerConfigHotApplyService(
                new ServerConfigHotApplyService.Callbacks() {
                    @Override
                    public void publishPolicy(CommittedSnapshot committed) {
                        if (policyCalls.incrementAndGet() == 1) throw new IllegalStateException("policy failed");
                    }

                    @Override
                    public void revalidateOnlineAccepted(CommittedSnapshot committed) {
                        acceptedCalls.incrementAndGet();
                    }
                });

        try {
            service.apply(mutation.committed());
            Assert.fail("first apply should report callback failure");
        } catch (IllegalStateException expected) {
            Assert.assertEquals(0L, service.appliedEpoch());
        }
        Assert.assertEquals(1, acceptedCalls.get());
        Assert.assertTrue(service.apply(mutation.committed()));
        Assert.assertEquals(2, policyCalls.get());
        Assert.assertEquals(2, acceptedCalls.get());
        Assert.assertEquals(mutation.committed().epoch, service.appliedEpoch());
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
