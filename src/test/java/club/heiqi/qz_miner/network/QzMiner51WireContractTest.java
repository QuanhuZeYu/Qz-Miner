package club.heiqi.qz_miner.network;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.objectgroup.ObjectGroupMode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 首个 5.1.0 的协议、framing、ordinal、code 与 mode bit 汇总基线。 */
public class QzMiner51WireContractTest {

    @Test
    public void protocolVersionsAndDeclaredFramesRemainFrozen() {
        Assert.assertEquals(0, PacketChainConfigRequest.LEGACY_PROTOCOL_VERSION);
        Assert.assertEquals(2, PacketChainConfigRequest.PROTOCOL_VERSION);
        Assert.assertEquals(8, PacketChainConfigRequest.LEGACY_PAYLOAD_BYTES);
        Assert.assertEquals(16, PacketChainConfigRequest.EXTENDED_PAYLOAD_BYTES);
        Assert.assertEquals(0, PacketChainConfigSync.LEGACY_PROTOCOL_VERSION);
        Assert.assertEquals(2, PacketChainConfigSync.PROTOCOL_VERSION);
        Assert.assertEquals(12, PacketChainConfigSync.LEGACY_PAYLOAD_BYTES);
        Assert.assertEquals(20, PacketChainConfigSync.EXTENDED_PAYLOAD_BYTES);
        Assert.assertEquals(2, ObjectGroupWireConfig.PROTOCOL_VERSION);
        Assert.assertEquals(32 * 1024, ObjectGroupWireConfig.MAX_PAYLOAD_BYTES);
        Assert.assertEquals(1024, ObjectGroupWireConfig.MAX_STRING_BYTES);
        Assert.assertEquals(25, PacketObjectGroupConfigSync.FIXED_PAYLOAD_BYTES);

        Assert.assertEquals(4, AutoToolSwapProtocol.PROTOCOL_VERSION);
        Assert.assertEquals(12, PacketAutoToolSwapRoundStart.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(96, PacketAutoToolSwapIntent.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(44, PacketAutoToolSwapRoundResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(56, PacketAutoToolSwapActionResult.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(36, PacketAutoToolSwapRoundPhase.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(60, PacketAutoToolSwapTakeoverRequest.FIXED_PAYLOAD_BYTES);
    }

    /** RoundStart encoder 与 decoder 必须共同服从独立的 5.1.0 canonical fixture。 */
    @Test
    public void autoToolRoundStartMatchesCanonical510FixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
                + "1122334455667788");
        assertWire(fixture, new PacketAutoToolSwapRoundStart(4, 0x1122334455667788L));

        ByteBuf fixtureBuffer = Unpooled.wrappedBuffer(fixture);
        PacketAutoToolSwapRoundStart decoded = new PacketAutoToolSwapRoundStart();
        decoded.fromBytes(fixtureBuffer);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, fixtureBuffer.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x1122334455667788L, decoded.clientNonce);
    }

    /** Intent 的 v4 关联号、动作、槽位与两份完整指纹按 literal bytes 双向冻结。 */
    @Test
    public void autoToolIntentMatchesCanonical510FixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
                + "0102030405060708"
                + "1112131415161718"
                + "00000006"
                + "00000002"
                + "0000001d"
                + "2122232425262728"
                + "3132333435363738"
                + "4142434445464748"
                + "5152535455565758"
                + "6162636465666768"
                + "7172737475767778"
                + "090a0b0c0d0e0f10"
                + "191a1b1c1d1e1f20");
        AutoToolSwapContentFingerprint anchor = AutoToolSwapContentFingerprint.fromWire(
                0x2122232425262728L,
                0x3132333435363738L,
                0x4142434445464748L,
                0x5152535455565758L);
        AutoToolSwapContentFingerprint candidate = AutoToolSwapContentFingerprint.fromWire(
                0x6162636465666768L,
                0x7172737475767778L,
                0x090a0b0c0d0e0f10L,
                0x191a1b1c1d1e1f20L);
        AutoToolSwapIntent intent = new AutoToolSwapIntent(
                4,
                0x0102030405060708L,
                0x1112131415161718L,
                AutoToolSwapAction.TAKEOVER,
                2,
                29,
                anchor,
                candidate);
        assertWire(fixture, new PacketAutoToolSwapIntent(intent));

        ByteBuf fixtureBuffer = Unpooled.wrappedBuffer(fixture);
        PacketAutoToolSwapIntent decoded = new PacketAutoToolSwapIntent();
        decoded.fromBytes(fixtureBuffer);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, fixtureBuffer.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(0x1112131415161718L, decoded.actionSequence);
        Assert.assertEquals(6, decoded.actionCode);
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER,
                AutoToolSwapAction.fromWireCode(decoded.actionCode));
        Assert.assertEquals(2, decoded.anchorSlot);
        Assert.assertEquals(29, decoded.candidateSlot);
        Assert.assertEquals(0x2122232425262728L, decoded.anchorFingerprintFirst);
        Assert.assertEquals(0x3132333435363738L, decoded.anchorFingerprintSecond);
        Assert.assertEquals(0x4142434445464748L, decoded.anchorFingerprintThird);
        Assert.assertEquals(0x5152535455565758L, decoded.anchorFingerprintFourth);
        Assert.assertEquals(0x6162636465666768L, decoded.candidateFingerprintFirst);
        Assert.assertEquals(0x7172737475767778L, decoded.candidateFingerprintSecond);
        Assert.assertEquals(0x090a0b0c0d0e0f10L, decoded.candidateFingerprintThird);
        Assert.assertEquals(0x191a1b1c1d1e1f20L, decoded.candidateFingerprintFourth);

        AutoToolSwapIntent decodedSemantic = new AutoToolSwapIntent(
                decoded.protocolVersion,
                decoded.serverRoundId,
                decoded.actionSequence,
                AutoToolSwapAction.fromWireCode(decoded.actionCode),
                decoded.anchorSlot,
                decoded.candidateSlot,
                AutoToolSwapContentFingerprint.fromWire(
                        decoded.anchorFingerprintFirst,
                        decoded.anchorFingerprintSecond,
                        decoded.anchorFingerprintThird,
                        decoded.anchorFingerprintFourth),
                AutoToolSwapContentFingerprint.fromWire(
                        decoded.candidateFingerprintFirst,
                        decoded.candidateFingerprintSecond,
                        decoded.candidateFingerprintThird,
                        decoded.candidateFingerprintFourth));
        Assert.assertTrue(decodedSemantic.usesTakeoverRequestId());
        Assert.assertEquals(0x1112131415161718L, decodedSemantic.takeoverRequestId());
    }

    /** RoundResult 的 nonce、round、结果/状态码与两个水位 long 按 literal bytes 双向冻结。 */
    @Test
    public void autoToolRoundResultMatchesCanonical510FixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
                + "1112131415161718"
                + "2122232425262728"
                + "00000005"
                + "00000007"
                + "3132333435363738"
                + "4142434445464748");
        AutoToolSwapRoundResult result = new AutoToolSwapRoundResult(
                0x2122232425262728L,
                AutoToolSwapResultCode.SYNC_FAILED,
                AutoToolSwapRoundState.ORPHANED,
                0x3132333435363738L,
                0x4142434445464748L);
        assertWire(fixture, new PacketAutoToolSwapRoundResult(4, 0x1112131415161718L, result));

        ByteBuf fixtureBuffer = Unpooled.wrappedBuffer(fixture);
        PacketAutoToolSwapRoundResult decoded = new PacketAutoToolSwapRoundResult();
        decoded.fromBytes(fixtureBuffer);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, fixtureBuffer.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x1112131415161718L, decoded.clientNonce);
        Assert.assertEquals(0x2122232425262728L, decoded.serverRoundId);
        Assert.assertEquals(5, decoded.resultCode);
        Assert.assertEquals(AutoToolSwapResultCode.SYNC_FAILED,
                AutoToolSwapResultCode.fromWireCode(decoded.resultCode));
        Assert.assertEquals(7, decoded.roundState);
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED,
                AutoToolSwapRoundState.fromWireCode(decoded.roundState));
        Assert.assertEquals(0x3132333435363738L, decoded.nextActionSequence);
        Assert.assertEquals(0x4142434445464748L, decoded.serverTick);
    }

    /** ActionResult 的关联号、三类 code、槽位及服务端水位按 literal bytes 双向冻结。 */
    @Test
    public void autoToolActionResultMatchesCanonical510FixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
                + "0102030405060708"
                + "1112131415161718"
                + "00000007"
                + "00000002"
                + "00000005"
                + "00000003"
                + "0000001b"
                + "2122232425262728"
                + "3132333435363738");
        AutoToolSwapIntent intent = new AutoToolSwapIntent(
                4,
                0x0102030405060708L,
                0x1112131415161718L,
                AutoToolSwapAction.DECLINE_TAKEOVER,
                3,
                27,
                AutoToolSwapContentFingerprint.fromWire(1L, 2L, 3L, 4L),
                AutoToolSwapContentFingerprint.fromWire(5L, 6L, 7L, 8L));
        AutoToolSwapRoundResult result = new AutoToolSwapRoundResult(
                0x0102030405060708L,
                AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.CLOSING,
                0x2122232425262728L,
                0x3132333435363738L);
        assertWire(fixture, new PacketAutoToolSwapActionResult(intent, result));

        ByteBuf fixtureBuffer = Unpooled.wrappedBuffer(fixture);
        PacketAutoToolSwapActionResult decoded = new PacketAutoToolSwapActionResult();
        decoded.fromBytes(fixtureBuffer);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, fixtureBuffer.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(0x1112131415161718L, decoded.actionSequence);
        Assert.assertEquals(7, decoded.actionCode);
        Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER,
                AutoToolSwapAction.fromWireCode(decoded.actionCode));
        Assert.assertEquals(2, decoded.resultCode);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED,
                AutoToolSwapResultCode.fromWireCode(decoded.resultCode));
        Assert.assertEquals(5, decoded.roundState);
        Assert.assertEquals(AutoToolSwapRoundState.CLOSING,
                AutoToolSwapRoundState.fromWireCode(decoded.roundState));
        Assert.assertEquals(3, decoded.anchorSlot);
        Assert.assertEquals(27, decoded.candidateSlot);
        Assert.assertEquals(0x2122232425262728L, decoded.nextActionSequence);
        Assert.assertEquals(0x3132333435363738L, decoded.serverTick);
    }

    /** RoundPhase 的 round/phase sequence、phase ordinal、generation 与 tick 双向冻结。 */
    @Test
    public void autoToolRoundPhaseMatchesCanonical510FixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
                + "0102030405060708"
                + "1112131415161718"
                + "00000003"
                + "21222324"
                + "3132333435363738");
        PacketAutoToolSwapRoundPhase encoded = new PacketAutoToolSwapRoundPhase(
                4,
                0x0102030405060708L,
                0x1112131415161718L,
                ChainPhase.RUNNING.ordinal(),
                0x21222324,
                0x3132333435363738L);
        assertWire(fixture, encoded);

        ByteBuf fixtureBuffer = Unpooled.wrappedBuffer(fixture);
        PacketAutoToolSwapRoundPhase decoded = new PacketAutoToolSwapRoundPhase();
        decoded.fromBytes(fixtureBuffer);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, fixtureBuffer.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(0x1112131415161718L, decoded.phaseSequence);
        Assert.assertEquals(3, decoded.phaseOrdinal);
        Assert.assertEquals(ChainPhase.RUNNING, ChainPhase.values()[decoded.phaseOrdinal]);
        Assert.assertEquals(0x21222324, decoded.generation);
        Assert.assertEquals(0x3132333435363738L, decoded.serverTick);
    }

    /** TakeoverRequest 的 target identity、独立请求号与 deadline 按 literal bytes 双向冻结。 */
    @Test
    public void autoToolTakeoverRequestMatchesCanonical510FixtureBothWays() {
        byte[] fixture = hex(
                "00000004"
                + "0102030405060708"
                + "1112131415161718"
                + "21222324"
                + "31323334"
                + "0000007b"
                + "41424344"
                + "51525354"
                + "61626364"
                + "3132333435363738"
                + "4142434445464748");
        AutoToolSwapTakeoverRequest request = new AutoToolSwapTakeoverRequest(
                4,
                0x0102030405060708L,
                0x1112131415161718L,
                0x21222324,
                0x31323334,
                123,
                0x41424344,
                0x51525354,
                0x61626364,
                0x3132333435363738L,
                0x4142434445464748L);
        assertWire(fixture, new PacketAutoToolSwapTakeoverRequest(request));

        ByteBuf fixtureBuffer = Unpooled.wrappedBuffer(fixture);
        PacketAutoToolSwapTakeoverRequest decoded = new PacketAutoToolSwapTakeoverRequest();
        decoded.fromBytes(fixtureBuffer);
        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(0, fixtureBuffer.readableBytes());
        Assert.assertEquals(4, decoded.protocolVersion);
        Assert.assertEquals(0x0102030405060708L, decoded.serverRoundId);
        Assert.assertEquals(0x1112131415161718L, decoded.actionSequence);
        Assert.assertEquals(0x21222324, decoded.generation);
        Assert.assertEquals(0x31323334, decoded.targetX);
        Assert.assertEquals(123, decoded.targetY);
        Assert.assertEquals(0x41424344, decoded.targetZ);
        Assert.assertEquals(0x51525354, decoded.targetBlockId);
        Assert.assertEquals(0x61626364, decoded.targetBlockMetadata);
        Assert.assertEquals(0x3132333435363738L, decoded.serverTick);
        Assert.assertEquals(0x4142434445464748L, decoded.deadlineTick);

        AutoToolSwapTakeoverRequest decodedSemantic = new AutoToolSwapTakeoverRequest(
                decoded.protocolVersion,
                decoded.serverRoundId,
                decoded.actionSequence,
                decoded.generation,
                decoded.targetX,
                decoded.targetY,
                decoded.targetZ,
                decoded.targetBlockId,
                decoded.targetBlockMetadata,
                decoded.serverTick,
                decoded.deadlineTick);
        Assert.assertEquals(0x1112131415161718L, decodedSemantic.takeoverRequestId());
        Assert.assertTrue(decodedSemantic.matchesTarget(
                0x0102030405060708L,
                0x21222324,
                0x31323334,
                123,
                0x41424344,
                0x51525354,
                0x61626364));
    }

    @Test
    public void modeAndPhaseOrdinalsRemainFrozen() {
        assertNames(new String[] {"CHAIN", "AREA", "INTERACT", "SPECIAL"}, ChainMode.values());
        assertNames(new String[] {"IDLE", "ARMED", "PLANNING", "RUNNING", "FINISHING"},
                ChainPhase.values());
        assertNames(new String[] {
                "CHAIN_BASE", "CHAIN_ORE", "CHAIN_LOGGING", "AREA_SAME_BLOCK",
                "AREA_HARVESTABLE_ALL", "AREA_ORE", "AREA_TUNNEL", "AREA_SECTION_CLEAR",
                "INTERACT_BASE", "INTERACT_CROP", "SPECIAL_LOOTGAMES_MINESWEEPER",
                "SPECIAL_GT_CABLE_REPLACE", "INTERACT_LIQUID_SOURCE",
                "INTERACT_FERTILIZE_IMMATURE_CROP"
        }, ChainSubMode.values());
    }

    @Test
    public void stableDirectionToolSwapCodesAndObjectGroupBitsRemainFrozen() {
        Assert.assertArrayEquals(new String[] {"look_direction", "hit_face"}, TunnelDirectionSource.ids());
        Assert.assertEquals(0, TunnelDirectionSource.LOOK_DIRECTION.wireCode());
        Assert.assertEquals(1, TunnelDirectionSource.HIT_FACE.wireCode());

        assertCodes(new int[] {1, 2, 3, 4, 5, 6, 7}, AutoToolSwapAction.values());
        assertCodes(new int[] {1, 2, 3, 4, 5, 6, 7}, AutoToolSwapRoundState.values());
        assertCodes(new int[] {1, 2, 3, 4, 5}, AutoToolSwapResultCode.values());

        Assert.assertArrayEquals(new String[] {
                "chain_base", "chain_ore", "chain_logging", "area_same_block", "area_ore",
                "interact_base", "interact_crop"
        }, ObjectGroupMode.ids());
        for (int bit = 0; bit < ObjectGroupMode.ids().length; bit++) {
            Assert.assertEquals(1L << bit,
                    ObjectGroupMode.toMask(Collections.singletonList(ObjectGroupMode.ids()[bit])));
        }
        Assert.assertEquals(0x007F, ObjectGroupMode.KNOWN_MASK);
    }

    @Test
    public void discriminatorZeroOneTwoFiveSixAndSevenKeepExactBytesAndFieldOrder() {
        assertWire("0102030401", new PacketKeyState(0x01020304, true));
        assertWire("00000002", new PacketChainModeSwitch(ChainMode.INTERACT));
        assertWire("0000000d", new PacketChainSubModeSwitch(
                ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP));

        assertWire(
                "010203041112131421222324313233344142434451525354",
                new PacketLootGamesMinesweeperPreviewRequest(
                        0x01020304,
                        new ChainTarget(0x11121314, 0x21222324, 0x31323334),
                        0x41424344,
                        0x51525354));

        assertWire(
                "0102030411121314212223243132333400000002"
                + "414243445152535461626364"
                + "717273748182838491929394",
                new PacketLootGamesMinesweeperPreviewResponse(
                        0x01020304,
                        new ChainTarget(0x11121314, 0x21222324, 0x31323334),
                        Arrays.asList(
                                new ChainTarget(0x41424344, 0x51525354, 0x61626364),
                                new ChainTarget(0x71727374, 0x81828384, 0x91929394))));

        assertWire("00000003010203041112131421222324",
                new PacketChainPhaseSnapshot(3, 0x01020304, 0x1112131421222324L));
    }

    private static void assertWire(String expectedHex, IMessage packet) {
        assertWire(hex(expectedHex), packet);
    }

    private static void assertWire(byte[] expected, IMessage packet) {
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);
        byte[] actual = new byte[buffer.readableBytes()];
        buffer.readBytes(actual);
        Assert.assertArrayEquals(expected, actual);
    }

    private static byte[] hex(String value) {
        Assert.assertEquals(0, value.length() % 2);
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            Assert.assertTrue(high >= 0 && low >= 0);
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static void assertNames(String[] expected, Enum<?>[] actual) {
        Assert.assertEquals(expected.length, actual.length);
        for (int ordinal = 0; ordinal < expected.length; ordinal++) {
            Assert.assertEquals(expected[ordinal], actual[ordinal].name());
            Assert.assertEquals(ordinal, actual[ordinal].ordinal());
        }
    }

    private static void assertCodes(int[] expected, AutoToolSwapAction[] actual) {
        Assert.assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            Assert.assertEquals(actual[index].name(), expected[index], actual[index].wireCode());
        }
    }

    private static void assertCodes(int[] expected, AutoToolSwapRoundState[] actual) {
        Assert.assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            Assert.assertEquals(actual[index].name(), expected[index], actual[index].wireCode());
        }
    }

    private static void assertCodes(int[] expected, AutoToolSwapResultCode[] actual) {
        Assert.assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            Assert.assertEquals(actual[index].name(), expected[index], actual[index].wireCode());
        }
    }
}
