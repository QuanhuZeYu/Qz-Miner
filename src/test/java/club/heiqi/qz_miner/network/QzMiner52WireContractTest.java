package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.selection.CuboidSelection;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 严格 5.2 新增 framing 与保留 code/ordinal 合同。 */
public class QzMiner52WireContractTest {

    @Test
    public void strict52FramesAndCodesRemainExplicit() {
        Assert.assertEquals(12, PacketAutoToolSwapRoundStart.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(96, PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(44, PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(56, PacketAutoToolSwapActionResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(36, PacketAutoToolSwapRoundPhase.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(20, PacketCuboidSelectionRequest.PAYLOAD_BYTES);
        Assert.assertEquals(56, PacketCuboidSelectionSync.PAYLOAD_BYTES);
        Assert.assertEquals(14, ChainSubMode.AREA_CUBOID_CLEAR.ordinal());
        assertCodes(new int[] {3, 4}, AutoToolSwapAction.values());
        assertCodes(new int[] {1, 2, 4, 5, 6, 7}, AutoToolSwapRoundState.values());
        assertCodes(new int[] {1, 3}, AutoToolSwapResultCode.values());
    }

    @Test
    public void cuboidRequestMatchesCanonicalFixtureBothWays() {
        byte[] fixture = hex("0000000100000002fffffffe0000007b10203040");
        assertWire(fixture, new PacketCuboidSelectionRequest(2, -2, 123, 0x10203040));
        PacketCuboidSelectionRequest decoded = new PacketCuboidSelectionRequest();
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        decoded.fromBytes(input);
        Assert.assertTrue(decoded.rawValid);
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(1, decoded.protocolVersion);
        Assert.assertEquals(2, decoded.pointIndex);
        Assert.assertEquals(-2, decoded.x);
        Assert.assertEquals(123, decoded.y);
        Assert.assertEquals(0x10203040, decoded.z);
    }

    @Test
    public void cuboidSyncMatchesCanonicalFixtureBothWays() {
        CuboidSelection selection = CuboidSelection.empty()
                .select(1, -1, -2, 3, 4, 100).getSelection()
                .select(2, -1, 5, 6, 7, 1000).getSelection();
        PacketCuboidSelectionSync packet = new PacketCuboidSelectionSync(selection, true, "accepted");
        ByteBuf encoded = Unpooled.buffer();
        packet.toBytes(encoded);
        Assert.assertEquals(PacketCuboidSelectionSync.PAYLOAD_BYTES, encoded.readableBytes());

        PacketCuboidSelectionSync decoded = new PacketCuboidSelectionSync();
        decoded.fromBytes(encoded.copy());
        Assert.assertTrue(decoded.rawValid);
        Assert.assertEquals(2L, decoded.revision);
        Assert.assertEquals(1, decoded.acceptedFlag);
        Assert.assertEquals(3, decoded.pointMask);
        Assert.assertEquals(-1, decoded.point1Dimension);
        Assert.assertEquals(-2, decoded.point1X);
        Assert.assertEquals(7, decoded.point2Z);
    }

    private static void assertWire(byte[] expected, IMessage message) {
        ByteBuf encoded = Unpooled.buffer();
        message.toBytes(encoded);
        byte[] actual = new byte[encoded.readableBytes()];
        encoded.readBytes(actual);
        Assert.assertArrayEquals(expected, actual);
    }

    private static byte[] hex(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }

    private static void assertCodes(int[] expected, Object[] values) {
        Assert.assertEquals(expected.length, values.length);
        for (int i = 0; i < expected.length; i++) {
            Object value = values[i];
            int actual = value instanceof AutoToolSwapAction
                    ? ((AutoToolSwapAction) value).wireCode()
                    : value instanceof AutoToolSwapRoundState
                            ? ((AutoToolSwapRoundState) value).wireCode()
                            : ((AutoToolSwapResultCode) value).wireCode();
            Assert.assertEquals(expected[i], actual);
        }
    }
}
