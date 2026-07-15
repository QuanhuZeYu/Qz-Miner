package club.heiqi.qz_miner.mixins;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 自动工具原版事务 Mixin 的 client-only 与注入点结构门禁。 */
public class AutoToolSwapMixinStructureTest {

    @Test
    public void destroyHookUsesBooleanReturnAndNetworkHooksObserveVanillaReturn() throws Exception {
        String controller = source("src/main/java/club/heiqi/qz_miner/mixins/client/MixinPlayerControllerMPToolSwap.java");
        String network = source("src/main/java/club/heiqi/qz_miner/mixins/client/MixinNetHandlerPlayClientToolSwap.java");
        Assert.assertTrue(controller.contains("onPlayerDestroyBlock(IIII)Z"));
        Assert.assertTrue(controller.contains("@At(\"RETURN\")"));
        Assert.assertTrue(controller.contains("getReturnValueZ()"));
        Assert.assertTrue(network.contains("C0EPacketClickWindow"));
        Assert.assertTrue(network.contains("func_149547_f()"));
        Assert.assertTrue(network.contains("handleConfirmTransaction"));
        Assert.assertTrue(network.contains("handleSetSlot"));
        Assert.assertTrue(network.contains("handleWindowItems"));
        Assert.assertEquals(3, occurrences(network, "at = @At(\"RETURN\")"));
    }

    @Test
    public void bothMixinsAreRegisteredOnlyInClientArray() throws Exception {
        String json = source("src/main/resources/mixins.qz_miner.json");
        int client = json.indexOf("\"client\"");
        int server = json.indexOf("\"server\"");
        int playerMixin = json.indexOf("client.MixinPlayerControllerMPToolSwap");
        int networkMixin = json.indexOf("client.MixinNetHandlerPlayClientToolSwap");
        Assert.assertTrue(client >= 0 && playerMixin > client && playerMixin < server);
        Assert.assertTrue(networkMixin > client && networkMixin < server);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        for (int at = value.indexOf(token); at >= 0; at = value.indexOf(token, at + token.length())) count++;
        return count;
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
