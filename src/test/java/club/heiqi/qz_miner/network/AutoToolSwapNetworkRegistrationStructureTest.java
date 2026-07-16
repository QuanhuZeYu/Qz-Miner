package club.heiqi.qz_miner.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

/** 自动工具换位 packet 仅追加注册且 side 固定的结构门禁。 */
public class AutoToolSwapNetworkRegistrationStructureTest {

    @Test
    public void toolSwapPacketsAppendAfterExistingPacketsInProtocolOrder() throws Exception {
        String source = source();
        int existingTail = source.indexOf("PacketObjectGroupConfigSync.Handler.class");
        int roundStart = source.indexOf("PacketAutoToolSwapRoundStart.Handler.class");
        int intent = source.indexOf("PacketAutoToolSwapIntent.Handler.class");
        int roundResult = source.indexOf("PacketAutoToolSwapRoundResult.Handler.class");
        int actionResult = source.indexOf("PacketAutoToolSwapActionResult.Handler.class");
        int roundPhase = source.indexOf("PacketAutoToolSwapRoundPhase.Handler.class");
        int takeover = source.indexOf("PacketAutoToolSwapTakeoverRequest.Handler.class");

        Assert.assertTrue(existingTail >= 0);
        Assert.assertTrue(existingTail < roundStart);
        Assert.assertTrue(roundStart < intent);
        Assert.assertTrue(intent < roundResult);
        Assert.assertTrue(roundResult < actionResult);
        Assert.assertTrue(actionResult < roundPhase);
        Assert.assertTrue(roundPhase < takeover);
        assertSide(source, roundStart, "Side.SERVER");
        assertSide(source, intent, "Side.SERVER");
        assertSide(source, roundResult, "Side.CLIENT");
        assertSide(source, actionResult, "Side.CLIENT");
        assertSide(source, roundPhase, "Side.CLIENT");
        assertSide(source, takeover, "Side.CLIENT");
    }

    private static void assertSide(String source, int registrationStart, String expectedSide) {
        int nextRegistration = source.indexOf("network.registerMessage", registrationStart + 1);
        int registrationEnd = nextRegistration < 0 ? source.length() : nextRegistration;
        Assert.assertTrue(source.substring(registrationStart, registrationEnd).contains(expectedSide));
    }

    private static String source() throws Exception {
        return new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/network/NetworkMain.java").toPath()), StandardCharsets.UTF_8);
    }
}
