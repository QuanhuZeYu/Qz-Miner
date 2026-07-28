package club.heiqi.qz_miner.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 子模式 ordinal 单 int wire framing 与边界解码回归。 */
public class PacketChainSubModeSwitchProtocolTest {

    @Test
    public void allLegacyAndAppendedOrdinalsRoundTripAsExactlyFourBytes() {
        for (ChainSubMode mode : ChainSubMode.values()) {
            PacketChainSubModeSwitch source = new PacketChainSubModeSwitch(mode);
            ByteBuf buffer = Unpooled.buffer(4);
            source.toBytes(buffer);

            Assert.assertEquals(mode.name(), 4, buffer.readableBytes());
            PacketChainSubModeSwitch decoded = new PacketChainSubModeSwitch();
            decoded.fromBytes(buffer);
            Assert.assertEquals(mode.name(), mode.ordinal(), decoded.subModeOrdinal);
            Assert.assertSame(mode.name(), mode, ChainSubMode.values()[decoded.subModeOrdinal]);
        }
    }

    @Test
    public void packetKeepsSingleIntAndBoundsCheckedUnknownOrdinalFallback() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/network/PacketChainSubModeSwitch.java").toPath()),
                StandardCharsets.UTF_8);

        Assert.assertTrue(source.contains("subModeOrdinal = buf.readInt();"));
        Assert.assertTrue(source.contains("buf.writeInt(subModeOrdinal);"));
        Assert.assertTrue(source.contains("subModeOrdinal >= 0 && subModeOrdinal < subModes.length"));
        Assert.assertTrue(source.contains("? subModes[subModeOrdinal]"));
        Assert.assertTrue(source.contains(": null;"));
        Assert.assertFalse(source.contains("writeByte(subModeOrdinal)"));
        Assert.assertFalse(source.contains("writeShort(subModeOrdinal)"));
    }

    @Test
    public void modeHandlersRejectStaleConnectionBeforeMutatingPlayerState() throws Exception {
        assertEndpointGatePrecedesMutation("PacketChainModeSwitch.java", "setPlayerSelectedMode(playerId, mode)");
        assertEndpointGatePrecedesMutation(
                "PacketChainSubModeSwitch.java", "setPlayerSelectedSubMode(playerId, subMode)");
    }

    private static void assertEndpointGatePrecedesMutation(String fileName, String mutation) throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/network/" + fileName).toPath()), StandardCharsets.UTF_8);
        int endpointCapture = source.indexOf("new WeakReference<EntityPlayerMP>(player)");
        int identityGate = source.indexOf("current != captured");
        int mutationIndex = source.indexOf(mutation);

        Assert.assertTrue(fileName + " must capture the connection endpoint", endpointCapture >= 0);
        Assert.assertTrue(fileName + " must reject stale endpoints", identityGate > endpointCapture);
        Assert.assertTrue(fileName + " must gate before state mutation", mutationIndex > identityGate);
    }
}
