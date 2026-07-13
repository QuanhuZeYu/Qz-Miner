package club.heiqi.qz_miner.client;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.Assert;
import org.junit.Test;

/** 守卫自动工具控制器的客户端所有权、按键顺序和生命周期边界。 */
public class AutoToolClientWiringStructureTest {

    @Test
    public void clientProxyOwnsOneControllerAndKeyListenerOwnsTheOnlyTickCall() throws Exception {
        String proxy = source("src/main/java/club/heiqi/qz_miner/ClientProxy.java");
        String key = source("src/main/java/club/heiqi/qz_miner/client/KeyListener.java");
        String connection = source("src/main/java/club/heiqi/qz_miner/client/ClientConnectionListener.java");
        Assert.assertEquals(1, count(proxy, "new AutoToolClientController()"));
        Assert.assertTrue(proxy.contains("new KeyListener(autoToolClientController).register()"));
        Assert.assertEquals(1, count(key, "autoToolController.tick()"));
        Assert.assertEquals(0, count(proxy, "autoToolClientController.tick()"));
        Assert.assertEquals(0, count(connection, "autoToolClientController.tick()"));
    }

    @Test
    public void keyEdgesKeepRequiredPacketAndControllerOrder() throws Exception {
        String key = source("src/main/java/club/heiqi/qz_miner/client/KeyListener.java");
        int pressedPacket = key.indexOf("new PacketKeyState(KEY_CHAIN, true)");
        int pressedController = key.indexOf("onChainKeyChanged(true)");
        int releasedController = key.indexOf("onChainKeyChanged(false)");
        int releasedPacket = key.indexOf("new PacketKeyState(KEY_CHAIN, false)");
        Assert.assertTrue(pressedPacket >= 0 && pressedPacket < pressedController);
        Assert.assertTrue(releasedController >= 0 && releasedController < releasedPacket);
    }

    @Test
    public void lifecycleCleanupResetsControllerThroughClientProxyOnly() throws Exception {
        String proxy = source("src/main/java/club/heiqi/qz_miner/ClientProxy.java");
        String connection = source("src/main/java/club/heiqi/qz_miner/client/ClientConnectionListener.java");
        String common = source("src/main/java/club/heiqi/qz_miner/CommonProxy.java");
        Assert.assertTrue(connection.contains("ClientProxy.resetAutoToolLifecycle()"));
        Assert.assertTrue(proxy.contains("autoToolClientController.resetLifecycle()"));
        Assert.assertFalse(common.contains("AutoToolClientController"));
        Assert.assertFalse(common.contains("resetAutoToolLifecycle"));
    }

    private static String source(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), Charset.forName("UTF-8"));
    }

    private static int count(String source, String value) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(value, index)) >= 0; index += value.length()) {
            count++;
        }
        return count;
    }
}
