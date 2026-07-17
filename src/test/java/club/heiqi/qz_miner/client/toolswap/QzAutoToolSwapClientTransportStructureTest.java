package club.heiqi.qz_miner.client.toolswap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** Qz C2S transport 的已接线发送边界结构门禁。 */
public class QzAutoToolSwapClientTransportStructureTest {

    @Test
    public void transportOnlySendsQzRoundStartAndIntentPackets() throws Exception {
        String source = source("QzAutoToolSwapClientTransport.java");

        Assert.assertTrue(source.contains("MyMod.networkMain == null"));
        Assert.assertTrue(source.contains("new PacketAutoToolSwapRoundStart(clientNonce)"));
        Assert.assertTrue(source.contains("new PacketAutoToolSwapIntent(intent)"));
        Assert.assertEquals(2, count(source, ".sendToServer("));
        Assert.assertFalse(source.contains("windowClick"));
    }

    @Test
    public void transportBoundaryDoesNotExposeForgePacketsToController() throws Exception {
        String source = source("AutoToolSwapClientTransport.java");

        Assert.assertTrue(source.contains("boolean sendRoundStart(long clientNonce)"));
        Assert.assertTrue(source.contains("boolean sendIntent(AutoToolSwapIntent intent)"));
        Assert.assertFalse(source.contains("PacketAutoToolSwap"));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/client/toolswap/" + name).toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String value, String needle) {
        int matches = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            matches++;
            index += needle.length();
        }
        return matches;
    }
}
