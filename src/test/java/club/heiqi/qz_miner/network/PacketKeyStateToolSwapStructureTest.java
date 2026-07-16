package club.heiqi.qz_miner.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 按键 packet 在同一主线程 FIFO 内解析 round 并固化到事件的结构门禁。 */
public class PacketKeyStateToolSwapStructureTest {

    @Test
    public void roundResolutionPrecedesStateWriteAndEventPublishInsideDispatcher() throws Exception {
        String source = source();
        int dispatcher = source.indexOf("ServerMainThreadDispatcher.run(() -> {");
        int resolve = source.indexOf("AutoToolSwapKeyStateBridge.onKeyState(player, pressed)");
        int stateWrite = source.indexOf("setPlayerChainKeyPressed");
        int keyEvent = source.indexOf("new ChainKeyPressed(");
        int cleanupEvent = source.indexOf("new LifecycleCleanup(");

        Assert.assertTrue(dispatcher >= 0);
        Assert.assertTrue(dispatcher < resolve);
        Assert.assertTrue(resolve < stateWrite);
        Assert.assertTrue(stateWrite < keyEvent);
        Assert.assertTrue(source.substring(keyEvent, cleanupEvent).contains("serverRoundId"));
        Assert.assertTrue(source.substring(cleanupEvent).contains("serverRoundId"));
        Assert.assertEquals("fresh key 必须仍只经同一 bridge 解析新 round", 1,
                occurrences(source, "AutoToolSwapKeyStateBridge.onKeyState(player, pressed)"));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/network/PacketKeyState.java").toPath()), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        for (int at = value.indexOf(token); at >= 0; at = value.indexOf(token, at + token.length())) count++;
        return count;
    }
}
