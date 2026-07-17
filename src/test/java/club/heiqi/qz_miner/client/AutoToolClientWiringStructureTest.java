package club.heiqi.qz_miner.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.CommonProxy;

/** 自动工具客户端接线、按键顺序与 common 分侧结构门禁。 */
public class AutoToolClientWiringStructureTest {

    @Test
    public void keyListenerNotifiesEdgesAndAdvancesOncePerEndPathWithoutChangingSelection() throws Exception {
        String source = source("src/main/java/club/heiqi/qz_miner/client/KeyListener.java");
        Assert.assertTrue(source.contains("autoToolSwapAdapter.onChainKeyState(pressed)"));
        Assert.assertTrue(source.indexOf("autoToolSwapAdapter.onChainKeyState(pressed)")
                < source.indexOf("new PacketKeyState(KEY_CHAIN, true)"));
        Assert.assertEquals(2, occurrences(source, "autoToolSwapAdapter.onClientTick()"));
        int deferredTick = source.indexOf("autoToolSwapAdapter.onClientTick()");
        int freshKey = source.indexOf("sendFreshChainKeyPressedToServer()", deferredTick);
        Assert.assertTrue("RoundStart tick 必须先于 fresh key=true", deferredTick < freshKey);
        Assert.assertFalse(source.substring(deferredTick, freshKey).contains("onChainKeyState(true)"));
        Assert.assertFalse(source.contains("currentItem ="));
    }

    @Test
    public void proxyInstallsOneAdapterAndConfigAndLifecycleNotifyIt() throws Exception {
        String proxy = source("src/main/java/club/heiqi/qz_miner/ClientProxy.java");
        String config = source("src/main/java/club/heiqi/qz_miner/client/ClientConfigChangeListener.java");
        String lifecycle = source("src/main/java/club/heiqi/qz_miner/client/ClientConnectionListener.java");
        Assert.assertEquals(1, occurrences(proxy, "new AutoToolSwapClientAdapter("));
        Assert.assertTrue(proxy.contains("new QzAutoToolSwapClientTransport()"));
        Assert.assertFalse(proxy.contains("AutoToolSwapClient" + "ProtocolState"));
        Assert.assertTrue(proxy.contains("AutoToolSwapHooks.install(autoToolSwapAdapter)"));
        Assert.assertTrue(proxy.contains("new KeyListener(autoToolSwapAdapter)"));
        Assert.assertTrue(config.contains("autoToolSwapAdapter.onConfigChanged"));
        Assert.assertTrue(lifecycle.contains("runCleanupStep(\"auto-tool-swap\""));
        Assert.assertTrue(lifecycle.contains("resetForLifecycle()"));
        Assert.assertTrue(proxy.contains("handleClientAutoToolSwapRoundResult"));
        Assert.assertTrue(proxy.contains("handleClientAutoToolSwapActionResult"));
        Assert.assertTrue(proxy.contains("handleClientAutoToolSwapRoundPhase"));
        Assert.assertTrue(proxy.contains("handleClientAutoToolSwapTakeoverRequest"));
        Assert.assertTrue(proxy.contains("autoToolSwapAdapter.onTakeoverRequest"));
        Assert.assertTrue(proxy.contains("AUTO_TOOL_SWAP_LIFECYCLE_GATE"));
    }

    @Test
    public void adapterIsThinAndReducerIsTheOnlyMutableBusinessAuthority() throws Exception {
        String adapter = source("src/main/java/club/heiqi/qz_miner/client/toolswap/AutoToolSwapClientAdapter.java");
        String reducer = source("src/main/java/club/heiqi/qz_miner/client/toolswap/AutoToolSwapClientReducer.java");
        String validator = source(
                "src/main/java/club/heiqi/qz_miner/client/toolswap/AutoToolSwapClientProtocolValidator.java");

        Assert.assertEquals(1, occurrences(adapter, "private final AutoToolSwapClientReducer reducer;"));
        Assert.assertFalse(adapter.contains("private boolean keyDown"));
        Assert.assertFalse(adapter.contains("dedicatedRoundEnded"));
        Assert.assertFalse(adapter.contains("Retransmitted"));
        Assert.assertTrue(reducer.contains("abstract static class Event"));
        Assert.assertTrue(reducer.contains("final class Effect"));
        Assert.assertTrue(reducer.contains("class RoundContext"));
        Assert.assertEquals(0,
                club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientProtocolValidator.class
                        .getDeclaredFields().length);
        Assert.assertFalse(validator.contains("lastPhaseSequence"));
    }

    @Test
    public void commonProxyConstantPoolDoesNotLinkNewClientTypes() throws Exception {
        byte[] bytes = classBytes(CommonProxy.class);
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        Assert.assertFalse(constants.contains("client/toolswap/AutoToolSwapClientAdapter"));
        Assert.assertFalse(constants.contains("mixins/client/MixinPlayerControllerMPToolSwap"));
    }

    private static byte[] classBytes(Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Assert.assertNotNull(input);
            byte[] buffer = new byte[4096];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) output.write(buffer, 0, read);
            return output.toByteArray();
        }
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
