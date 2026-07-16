package club.heiqi.qz_miner.client.toolswap;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** ClientProxy 的自动工具 S2C world gate 结构合同。 */
public class ClientAutoToolSwapRuntimePacketDispatchTest {

    @Test
    public void proxyCapturesConnectionAndPublishesAllFourPacketsThroughWorldGate() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/ClientProxy.java").toPath()), StandardCharsets.UTF_8);
        Assert.assertEquals(4, occurrences(source, "ClientAutoToolSwapPacketDispatch.dispatch(token"));
        Assert.assertTrue(source.contains("AUTO_TOOL_SWAP_LIFECYCLE_GATE"));
        Assert.assertTrue(source.contains("captureForConnection(netHandler)"));
        Assert.assertTrue(source.contains("runIfWorldCurrentAndActive"));
        Assert.assertTrue(source.contains("autoToolSwapAdapter.onRoundResult"));
        Assert.assertTrue(source.contains("autoToolSwapAdapter.onActionResult"));
        Assert.assertTrue(source.contains("autoToolSwapAdapter.onRoundPhase"));
        Assert.assertTrue(source.contains("autoToolSwapAdapter.onTakeoverRequest"));
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        for (int index = value.indexOf(token); index >= 0; index = value.indexOf(token, index + token.length())) count++;
        return count;
    }
}
