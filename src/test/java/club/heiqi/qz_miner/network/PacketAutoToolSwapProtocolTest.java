package club.heiqi.qz_miner.network;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 自动工具换位五类固定 wire 帧的 framing 与原始字段回归。 */
public class PacketAutoToolSwapProtocolTest {

    @Test
    public void fiveFramesRoundTripWithExactDeclaredLengths() {
        AutoToolSwapContentFingerprint anchor = AutoToolSwapContentFingerprint.fromWire(1L, 2L, 3L, 4L);
        AutoToolSwapContentFingerprint candidate = AutoToolSwapContentFingerprint.fromWire(5L, 6L, 7L, 8L);
        AutoToolSwapIntent intent = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, 17L, 3L,
                AutoToolSwapAction.FREEZE, 2, 19, anchor, candidate);
        AutoToolSwapRoundResult result = new AutoToolSwapRoundResult(17L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FROZEN, 4L, 99L);

        PacketAutoToolSwapRoundStart start = decodeStart(new PacketAutoToolSwapRoundStart(11L));
        Assert.assertTrue(start.isRawValid());
        Assert.assertEquals(11L, start.clientNonce);

        PacketAutoToolSwapRoundResult round = decodeRoundResult(new PacketAutoToolSwapRoundResult(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 11L, result));
        Assert.assertTrue(round.isRawValid());
        Assert.assertEquals(11L, round.clientNonce);
        Assert.assertEquals(17L, round.serverRoundId);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED.wireCode(), round.resultCode);
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN.wireCode(), round.roundState);

        PacketAutoToolSwapIntent decodedIntent = decodeIntent(new PacketAutoToolSwapIntent(intent));
        Assert.assertTrue(decodedIntent.isRawValid());
        Assert.assertEquals(1L, decodedIntent.anchorFingerprintFirst);
        Assert.assertEquals(2L, decodedIntent.anchorFingerprintSecond);
        Assert.assertEquals(3L, decodedIntent.anchorFingerprintThird);
        Assert.assertEquals(4L, decodedIntent.anchorFingerprintFourth);
        Assert.assertEquals(5L, decodedIntent.candidateFingerprintFirst);
        Assert.assertEquals(6L, decodedIntent.candidateFingerprintSecond);
        Assert.assertEquals(7L, decodedIntent.candidateFingerprintThird);
        Assert.assertEquals(8L, decodedIntent.candidateFingerprintFourth);

        PacketAutoToolSwapActionResult action = decodeActionResult(new PacketAutoToolSwapActionResult(intent, result));
        Assert.assertTrue(action.isRawValid());
        Assert.assertEquals(3L, action.actionSequence);
        Assert.assertEquals(2, action.anchorSlot);
        Assert.assertEquals(19, action.candidateSlot);
        Assert.assertEquals(4L, action.nextActionSequence);

        PacketAutoToolSwapRoundPhase phase = decodeRoundPhase(new PacketAutoToolSwapRoundPhase(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 17L, 5L, 3, 7, 99L));
        Assert.assertTrue(phase.isRawValid());
        Assert.assertEquals(5L, phase.phaseSequence);
        Assert.assertEquals(3, phase.phaseOrdinal);
        Assert.assertEquals(7, phase.generation);
    }

    @Test
    public void allFramesRejectTruncatedAndTrailingPayloads() {
        assertInvalidStart(new PacketAutoToolSwapRoundStart(1L));
        AutoToolSwapRoundResult result = new AutoToolSwapRoundResult(2L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, 1L, 3L);
        assertInvalidRoundResult(new PacketAutoToolSwapRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 1L, result));
        AutoToolSwapIntent intent = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, 2L, 1L,
                AutoToolSwapAction.FREEZE, 0, 0,
                AutoToolSwapContentFingerprint.fromWire(1L, 2L, 3L, 4L),
                AutoToolSwapContentFingerprint.fromWire(5L, 6L, 7L, 8L));
        assertInvalidIntent(new PacketAutoToolSwapIntent(intent));
        assertInvalidActionResult(new PacketAutoToolSwapActionResult(intent, result));
        assertInvalidRoundPhase(new PacketAutoToolSwapRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION,
                2L, 1L, 0, 0, 3L));
    }

    @Test
    public void unknownWireCodesRemainRawForMainThreadFailClosedValidation() {
        ByteBuf intentBytes = Unpooled.buffer(PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        intentBytes.writeInt(AutoToolSwapProtocol.PROTOCOL_VERSION);
        intentBytes.writeLong(7L);
        intentBytes.writeLong(1L);
        intentBytes.writeInt(999);
        intentBytes.writeInt(0);
        intentBytes.writeInt(1);
        for (int i = 0; i < 8; i++) {
            intentBytes.writeLong(i + 1L);
        }
        PacketAutoToolSwapIntent intent = new PacketAutoToolSwapIntent();
        intent.fromBytes(intentBytes);
        Assert.assertTrue(intent.isRawValid());
        Assert.assertEquals(999, intent.actionCode);

        ByteBuf resultBytes = Unpooled.buffer(PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        resultBytes.writeInt(AutoToolSwapProtocol.PROTOCOL_VERSION);
        resultBytes.writeLong(1L);
        resultBytes.writeLong(7L);
        resultBytes.writeInt(998);
        resultBytes.writeInt(997);
        resultBytes.writeLong(2L);
        resultBytes.writeLong(3L);
        PacketAutoToolSwapRoundResult result = new PacketAutoToolSwapRoundResult();
        result.fromBytes(resultBytes);
        Assert.assertTrue(result.isRawValid());
        Assert.assertEquals(998, result.resultCode);
        Assert.assertEquals(997, result.roundState);
    }

    @Test
    public void closeRoundTripsWithoutChangingFiveFrameLengths() throws Exception {
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent close = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, 17L, 8L,
                AutoToolSwapAction.CLOSE, 2, 19, empty, empty);
        PacketAutoToolSwapIntent decoded = decodeIntent(new PacketAutoToolSwapIntent(close));

        Assert.assertEquals(AutoToolSwapAction.CLOSE.wireCode(), decoded.actionCode);
        Assert.assertEquals(12, PacketAutoToolSwapRoundStart.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(44, PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(96, PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(56, PacketAutoToolSwapActionResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(36, PacketAutoToolSwapRoundPhase.FIXED_PAYLOAD_BYTES);
    }

    private static PacketAutoToolSwapRoundStart decodeStart(PacketAutoToolSwapRoundStart source) {
        ByteBuf buffer = encode(source, PacketAutoToolSwapRoundStart.FIXED_PAYLOAD_BYTES);
        PacketAutoToolSwapRoundStart decoded = new PacketAutoToolSwapRoundStart();
        decoded.fromBytes(buffer);
        return decoded;
    }

    private static PacketAutoToolSwapRoundResult decodeRoundResult(PacketAutoToolSwapRoundResult source) {
        ByteBuf buffer = encode(source, PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        PacketAutoToolSwapRoundResult decoded = new PacketAutoToolSwapRoundResult();
        decoded.fromBytes(buffer);
        return decoded;
    }

    private static PacketAutoToolSwapIntent decodeIntent(PacketAutoToolSwapIntent source) {
        ByteBuf buffer = encode(source, PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        PacketAutoToolSwapIntent decoded = new PacketAutoToolSwapIntent();
        decoded.fromBytes(buffer);
        return decoded;
    }

    private static PacketAutoToolSwapActionResult decodeActionResult(PacketAutoToolSwapActionResult source) {
        ByteBuf buffer = encode(source, PacketAutoToolSwapActionResult.FIXED_PAYLOAD_BYTES);
        PacketAutoToolSwapActionResult decoded = new PacketAutoToolSwapActionResult();
        decoded.fromBytes(buffer);
        return decoded;
    }

    private static PacketAutoToolSwapRoundPhase decodeRoundPhase(PacketAutoToolSwapRoundPhase source) {
        ByteBuf buffer = encode(source, PacketAutoToolSwapRoundPhase.FIXED_PAYLOAD_BYTES);
        PacketAutoToolSwapRoundPhase decoded = new PacketAutoToolSwapRoundPhase();
        decoded.fromBytes(buffer);
        return decoded;
    }

    private static ByteBuf encode(IMessage source, int expectedLength) {
        ByteBuf buffer = Unpooled.buffer(expectedLength);
        source.toBytes(buffer);
        Assert.assertEquals(expectedLength, buffer.readableBytes());
        return buffer;
    }

    private static void assertInvalidStart(PacketAutoToolSwapRoundStart source) {
        ByteBuf bytes = encode(source, PacketAutoToolSwapRoundStart.FIXED_PAYLOAD_BYTES);
        bytes.writerIndex(bytes.writerIndex() - 1);
        PacketAutoToolSwapRoundStart truncated = new PacketAutoToolSwapRoundStart();
        truncated.fromBytes(bytes);
        Assert.assertFalse(truncated.isRawValid());
        ByteBuf trailing = encode(source, PacketAutoToolSwapRoundStart.FIXED_PAYLOAD_BYTES);
        trailing.writeByte(0);
        PacketAutoToolSwapRoundStart extra = new PacketAutoToolSwapRoundStart();
        extra.fromBytes(trailing);
        Assert.assertFalse(extra.isRawValid());
    }

    private static void assertInvalidRoundResult(PacketAutoToolSwapRoundResult source) {
        ByteBuf bytes = encode(source, PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        bytes.writerIndex(bytes.writerIndex() - 1);
        PacketAutoToolSwapRoundResult truncated = new PacketAutoToolSwapRoundResult();
        truncated.fromBytes(bytes);
        Assert.assertFalse(truncated.isRawValid());
        ByteBuf trailing = encode(source, PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        trailing.writeByte(0);
        PacketAutoToolSwapRoundResult extra = new PacketAutoToolSwapRoundResult();
        extra.fromBytes(trailing);
        Assert.assertFalse(extra.isRawValid());
    }

    private static void assertInvalidIntent(PacketAutoToolSwapIntent source) {
        ByteBuf bytes = encode(source, PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        bytes.writerIndex(bytes.writerIndex() - 1);
        PacketAutoToolSwapIntent truncated = new PacketAutoToolSwapIntent();
        truncated.fromBytes(bytes);
        Assert.assertFalse(truncated.isRawValid());
        ByteBuf trailing = encode(source, PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        trailing.writeByte(0);
        PacketAutoToolSwapIntent extra = new PacketAutoToolSwapIntent();
        extra.fromBytes(trailing);
        Assert.assertFalse(extra.isRawValid());
    }

    private static void assertInvalidActionResult(PacketAutoToolSwapActionResult source) {
        ByteBuf bytes = encode(source, PacketAutoToolSwapActionResult.FIXED_PAYLOAD_BYTES);
        bytes.writerIndex(bytes.writerIndex() - 1);
        PacketAutoToolSwapActionResult truncated = new PacketAutoToolSwapActionResult();
        truncated.fromBytes(bytes);
        Assert.assertFalse(truncated.isRawValid());
        ByteBuf trailing = encode(source, PacketAutoToolSwapActionResult.FIXED_PAYLOAD_BYTES);
        trailing.writeByte(0);
        PacketAutoToolSwapActionResult extra = new PacketAutoToolSwapActionResult();
        extra.fromBytes(trailing);
        Assert.assertFalse(extra.isRawValid());
    }

    private static void assertInvalidRoundPhase(PacketAutoToolSwapRoundPhase source) {
        ByteBuf bytes = encode(source, PacketAutoToolSwapRoundPhase.FIXED_PAYLOAD_BYTES);
        bytes.writerIndex(bytes.writerIndex() - 1);
        PacketAutoToolSwapRoundPhase truncated = new PacketAutoToolSwapRoundPhase();
        truncated.fromBytes(bytes);
        Assert.assertFalse(truncated.isRawValid());
        ByteBuf trailing = encode(source, PacketAutoToolSwapRoundPhase.FIXED_PAYLOAD_BYTES);
        trailing.writeByte(0);
        PacketAutoToolSwapRoundPhase extra = new PacketAutoToolSwapRoundPhase();
        extra.fromBytes(trailing);
        Assert.assertFalse(extra.isRawValid());
    }

}
