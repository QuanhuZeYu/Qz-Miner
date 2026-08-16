package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 5.3 继续冻结既有 framing、code 与 ordinal，不借调度重构改变 wire。 */
public class QzMiner53WireContractTest {

    @Test
    public void strict53FramesAndCodesRemainExplicit() {
        Assert.assertEquals(4, AutoToolSwapProtocol.PROTOCOL_VERSION);
        Assert.assertEquals(12, PacketAutoToolSwapRoundStart.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(96, PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(44, PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(56, PacketAutoToolSwapActionResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(36, PacketAutoToolSwapRoundPhase.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(20, PacketCuboidSelectionRequest.PAYLOAD_BYTES);
        Assert.assertEquals(56, PacketCuboidSelectionSync.PAYLOAD_BYTES);
        Assert.assertEquals(0, PacketChainConfigRequest.LEGACY_PROTOCOL_VERSION);
        Assert.assertEquals(2, PacketChainConfigRequest.PROTOCOL_VERSION);
        Assert.assertEquals(8, PacketChainConfigRequest.LEGACY_PAYLOAD_BYTES);
        Assert.assertEquals(16, PacketChainConfigRequest.EXTENDED_PAYLOAD_BYTES);
        Assert.assertEquals(0, PacketChainConfigSync.LEGACY_PROTOCOL_VERSION);
        Assert.assertEquals(2, PacketChainConfigSync.PROTOCOL_VERSION);
        Assert.assertEquals(12, PacketChainConfigSync.LEGACY_PAYLOAD_BYTES);
        Assert.assertEquals(20, PacketChainConfigSync.EXTENDED_PAYLOAD_BYTES);
        Assert.assertEquals(2, ObjectGroupWireConfig.PROTOCOL_VERSION);
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
        byte[] fixture = hex(
                "00000001"
              + "0000000000000002"
              + "00000001"
              + "00000000"
              + "00000003"
              + "ffffffff"
              + "fffffffe"
              + "00000003"
              + "00000004"
              + "ffffffff"
              + "00000005"
              + "00000006"
              + "00000007");
        PacketCuboidSelectionSync packet = new PacketCuboidSelectionSync();
        packet.revision = 2L;
        packet.acceptedFlag = 1;
        packet.reasonCode = 0;
        packet.pointMask = 3;
        packet.point1Dimension = -1;
        packet.point1X = -2;
        packet.point1Y = 3;
        packet.point1Z = 4;
        packet.point2Dimension = -1;
        packet.point2X = 5;
        packet.point2Y = 6;
        packet.point2Z = 7;
        assertWire(fixture, packet);

        PacketCuboidSelectionSync decoded = new PacketCuboidSelectionSync();
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        decoded.fromBytes(input);
        Assert.assertTrue(decoded.rawValid);
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(1, decoded.protocolVersion);
        Assert.assertEquals(2L, decoded.revision);
        Assert.assertEquals(1, decoded.acceptedFlag);
        Assert.assertEquals(0, decoded.reasonCode);
        Assert.assertEquals(3, decoded.pointMask);
        Assert.assertEquals(-1, decoded.point1Dimension);
        Assert.assertEquals(-2, decoded.point1X);
        Assert.assertEquals(3, decoded.point1Y);
        Assert.assertEquals(4, decoded.point1Z);
        Assert.assertEquals(-1, decoded.point2Dimension);
        Assert.assertEquals(5, decoded.point2X);
        Assert.assertEquals(6, decoded.point2Y);
        Assert.assertEquals(7, decoded.point2Z);
    }

    @Test
    public void chainConfigRequestMatchesLiteralLegacyAndV2Fixtures() {
        byte[] extended = hex("0000000c000001590000000200000001");
        assertWire(extended,
                new PacketChainConfigRequest(12, 345, TunnelDirectionSource.HIT_FACE));
        PacketChainConfigRequest decodedExtended = new PacketChainConfigRequest();
        ByteBuf extendedInput = Unpooled.wrappedBuffer(extended);
        decodedExtended.fromBytes(extendedInput);
        Assert.assertTrue(decodedExtended.rawValid);
        Assert.assertEquals(0, extendedInput.readableBytes());
        Assert.assertEquals(12, decodedExtended.requestedChainRadius);
        Assert.assertEquals(345, decodedExtended.requestedChainMaxBlocks);
        Assert.assertEquals(2, decodedExtended.protocolVersion);
        Assert.assertEquals(1, decodedExtended.tunnelDirectionCode);

        byte[] legacy = hex("0000000700000063");
        PacketChainConfigRequest decodedLegacy = new PacketChainConfigRequest();
        ByteBuf legacyInput = Unpooled.wrappedBuffer(legacy);
        decodedLegacy.fromBytes(legacyInput);
        Assert.assertTrue(decodedLegacy.rawValid);
        Assert.assertEquals(0, legacyInput.readableBytes());
        Assert.assertEquals(7, decodedLegacy.requestedChainRadius);
        Assert.assertEquals(99, decodedLegacy.requestedChainMaxBlocks);
        Assert.assertEquals(0, decodedLegacy.protocolVersion);
        Assert.assertEquals(0, decodedLegacy.tunnelDirectionCode);
    }

    @Test
    public void chainConfigSyncMatchesLiteralLegacyAndV2Fixtures() {
        byte[] extended = hex("0000000c00000159000000430000000200000001");
        assertWire(extended,
                new PacketChainConfigSync(12, 345, 67, TunnelDirectionSource.HIT_FACE));
        PacketChainConfigSync decodedExtended = new PacketChainConfigSync();
        ByteBuf extendedInput = Unpooled.wrappedBuffer(extended);
        decodedExtended.fromBytes(extendedInput);
        Assert.assertTrue(decodedExtended.rawValid);
        Assert.assertEquals(0, extendedInput.readableBytes());
        Assert.assertEquals(12, decodedExtended.chainRadius);
        Assert.assertEquals(345, decodedExtended.chainMaxBlocks);
        Assert.assertEquals(67, decodedExtended.matchedTargetCount);
        Assert.assertEquals(2, decodedExtended.protocolVersion);
        Assert.assertEquals(1, decodedExtended.tunnelDirectionCode);

        byte[] legacy = hex("000000070000006300000003");
        PacketChainConfigSync decodedLegacy = new PacketChainConfigSync();
        ByteBuf legacyInput = Unpooled.wrappedBuffer(legacy);
        decodedLegacy.fromBytes(legacyInput);
        Assert.assertTrue(decodedLegacy.rawValid);
        Assert.assertEquals(0, legacyInput.readableBytes());
        Assert.assertEquals(7, decodedLegacy.chainRadius);
        Assert.assertEquals(99, decodedLegacy.chainMaxBlocks);
        Assert.assertEquals(3, decodedLegacy.matchedTargetCount);
        Assert.assertEquals(0, decodedLegacy.protocolVersion);
        Assert.assertEquals(0, decodedLegacy.tunnelDirectionCode);
    }

    @Test
    public void objectGroupV2MatchesLiteralVariableFixtureBothWays() {
        byte[] fixture = hex(
                "00000002"
              + "0102030405060708"
              + "00000001"
              + "000161"
              + "0000000000000001"
              + "00000001"
              + "000178");
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        ObjectGroupWireConfig decoded = ObjectGroupWireConfig.read(input);
        Assert.assertTrue(decoded.isValid());
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(2, decoded.protocolVersion());
        Assert.assertEquals(0x0102030405060708L, decoded.revision());
        Assert.assertEquals(1, decoded.groups().size());
        Assert.assertEquals("a", decoded.groups().get(0).id());
        Assert.assertEquals(1L, decoded.groups().get(0).modeMask());
        Assert.assertEquals("x", decoded.groups().get(0).members().get(0));

        ByteBuf encoded = Unpooled.buffer();
        decoded.write(encoded);
        Assert.assertArrayEquals(fixture, bytes(encoded));
    }

    @Test
    public void roundStartMatchesCanonicalFixtureBothWays() {
        byte[] fixture = hex("000000044142434445464748");
        assertWire(fixture, new PacketAutoToolSwapRoundStart(0x4142434445464748L));

        PacketAutoToolSwapRoundStart decoded = new PacketAutoToolSwapRoundStart();
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        decoded.fromBytes(input);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x4142434445464748L, decoded.clientNonce);
    }

    @Test
    public void intentMatchesCanonicalFixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
              + "0102030405060708"
              + "1112131415161718"
              + "00000003"
              + "00000002"
              + "00000013"
              + "0000000000000001"
              + "0000000000000002"
              + "0000000000000003"
              + "0000000000000004"
              + "0000000000000005"
              + "0000000000000006"
              + "0000000000000007"
              + "0000000000000008");
        PacketAutoToolSwapIntent packet = new PacketAutoToolSwapIntent();
        packet.protocolVersion = 4;
        packet.serverRoundId = 0x0102030405060708L;
        packet.actionSequence = 0x1112131415161718L;
        packet.actionCode = 3;
        packet.anchorSlot = 2;
        packet.candidateSlot = 19;
        packet.anchorFingerprintFirst = 1L;
        packet.anchorFingerprintSecond = 2L;
        packet.anchorFingerprintThird = 3L;
        packet.anchorFingerprintFourth = 4L;
        packet.candidateFingerprintFirst = 5L;
        packet.candidateFingerprintSecond = 6L;
        packet.candidateFingerprintThird = 7L;
        packet.candidateFingerprintFourth = 8L;
        assertWire(fixture, packet);

        PacketAutoToolSwapIntent decoded = new PacketAutoToolSwapIntent();
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        decoded.fromBytes(input);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(0x1112131415161718L, decoded.actionSequence);
        Assert.assertEquals(3, decoded.actionCode);
        Assert.assertEquals(2, decoded.anchorSlot);
        Assert.assertEquals(19, decoded.candidateSlot);
        Assert.assertEquals(1L, decoded.anchorFingerprintFirst);
        Assert.assertEquals(2L, decoded.anchorFingerprintSecond);
        Assert.assertEquals(3L, decoded.anchorFingerprintThird);
        Assert.assertEquals(4L, decoded.anchorFingerprintFourth);
        Assert.assertEquals(5L, decoded.candidateFingerprintFirst);
        Assert.assertEquals(6L, decoded.candidateFingerprintSecond);
        Assert.assertEquals(7L, decoded.candidateFingerprintThird);
        Assert.assertEquals(8L, decoded.candidateFingerprintFourth);
    }

    @Test
    public void roundResultMatchesCanonicalFixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
              + "4142434445464748"
              + "0102030405060708"
              + "00000001"
              + "00000004"
              + "2122232425262728"
              + "3132333435363738");
        PacketAutoToolSwapRoundResult packet = new PacketAutoToolSwapRoundResult();
        packet.protocolVersion = 4;
        packet.clientNonce = 0x4142434445464748L;
        packet.serverRoundId = 0x0102030405060708L;
        packet.resultCode = 1;
        packet.roundState = 4;
        packet.nextActionSequence = 0x2122232425262728L;
        packet.serverTick = 0x3132333435363738L;
        assertWire(fixture, packet);

        PacketAutoToolSwapRoundResult decoded = new PacketAutoToolSwapRoundResult();
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        decoded.fromBytes(input);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x4142434445464748L, decoded.clientNonce);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(1, decoded.resultCode);
        Assert.assertEquals(4, decoded.roundState);
        Assert.assertEquals(0x2122232425262728L, decoded.nextActionSequence);
        Assert.assertEquals(0x3132333435363738L, decoded.serverTick);
    }

    @Test
    public void actionResultMatchesCanonicalFixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
              + "0102030405060708"
              + "1112131415161718"
              + "00000003"
              + "00000001"
              + "00000004"
              + "00000002"
              + "00000013"
              + "2122232425262728"
              + "3132333435363738");
        PacketAutoToolSwapActionResult packet = new PacketAutoToolSwapActionResult();
        packet.protocolVersion = 4;
        packet.serverRoundId = 0x0102030405060708L;
        packet.actionSequence = 0x1112131415161718L;
        packet.actionCode = 3;
        packet.resultCode = 1;
        packet.roundState = 4;
        packet.anchorSlot = 2;
        packet.candidateSlot = 19;
        packet.nextActionSequence = 0x2122232425262728L;
        packet.serverTick = 0x3132333435363738L;
        assertWire(fixture, packet);

        PacketAutoToolSwapActionResult decoded = new PacketAutoToolSwapActionResult();
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        decoded.fromBytes(input);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(0x1112131415161718L, decoded.actionSequence);
        Assert.assertEquals(3, decoded.actionCode);
        Assert.assertEquals(1, decoded.resultCode);
        Assert.assertEquals(4, decoded.roundState);
        Assert.assertEquals(2, decoded.anchorSlot);
        Assert.assertEquals(19, decoded.candidateSlot);
        Assert.assertEquals(0x2122232425262728L, decoded.nextActionSequence);
        Assert.assertEquals(0x3132333435363738L, decoded.serverTick);
    }

    @Test
    public void roundPhaseMatchesCanonicalFixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
              + "0102030405060708"
              + "5152535455565758"
              + "00000003"
              + "10203040"
              + "3132333435363738");
        PacketAutoToolSwapRoundPhase packet = new PacketAutoToolSwapRoundPhase(
                4, 0x0102030405060708L, 0x5152535455565758L,
                3, 0x10203040, 0x3132333435363738L);
        assertWire(fixture, packet);

        PacketAutoToolSwapRoundPhase decoded = new PacketAutoToolSwapRoundPhase();
        ByteBuf input = Unpooled.wrappedBuffer(fixture);
        decoded.fromBytes(input);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, input.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(0x5152535455565758L, decoded.phaseSequence);
        Assert.assertEquals(3, decoded.phaseOrdinal);
        Assert.assertEquals(0x10203040, decoded.generation);
        Assert.assertEquals(0x3132333435363738L, decoded.serverTick);
    }

    private static void assertWire(byte[] expected, IMessage message) {
        ByteBuf encoded = Unpooled.buffer();
        message.toBytes(encoded);
        Assert.assertArrayEquals(expected, bytes(encoded));
    }

    private static byte[] bytes(ByteBuf encoded) {
        byte[] actual = new byte[encoded.readableBytes()];
        encoded.readBytes(actual);
        return actual;
    }

    private static byte[] hex(String text) {
        Assert.assertEquals("hex fixture must contain complete bytes", 0, text.length() % 2);
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
