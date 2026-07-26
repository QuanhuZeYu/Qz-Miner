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
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
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
        ByteBuf buffer = Unpooled.buffer();
        packet.toBytes(buffer);
        byte[] actual = new byte[buffer.readableBytes()];
        buffer.readBytes(actual);
        Assert.assertArrayEquals(hex(expectedHex), actual);
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
