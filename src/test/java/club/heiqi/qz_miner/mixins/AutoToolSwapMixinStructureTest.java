package club.heiqi.qz_miner.mixins;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 自动工具只保留首块成功 Mixin，网络事务路径必须为零。 */
public class AutoToolSwapMixinStructureTest {

    @Test
    public void destroyHookRemainsButNetworkMixinIsRemoved() throws Exception {
        String controller = source("src/main/java/club/heiqi/qz_miner/mixins/client/MixinPlayerControllerMPToolSwap.java");
        Assert.assertTrue(controller.contains("onPlayerDestroyBlock(IIII)Z"));
        Assert.assertTrue(controller.contains("getReturnValueZ()"));
        Assert.assertFalse(new File("src/main/java/club/heiqi/qz_miner/mixins/client/MixinNetHandlerPlayClientToolSwap.java").exists());
    }

    @Test
    public void clientMixinListDoesNotRegisterVanillaNetworkPath() throws Exception {
        String json = source("src/main/resources/mixins.qz_miner.json");
        Assert.assertTrue(json.contains("client.MixinPlayerControllerMPToolSwap"));
        Assert.assertFalse(json.contains("MixinNetHandlerPlayClientToolSwap"));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
