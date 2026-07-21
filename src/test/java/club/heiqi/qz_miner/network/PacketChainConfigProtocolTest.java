package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.planner.TunnelDirectionSource;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 连锁配置 legacy/v2 framing 与四象限兼容合同。 */
public class PacketChainConfigProtocolTest {

    @Test
    public void c2sExtendedIsSixteenBytesAndKeepsLegacyPrefix() {
        PacketChainConfigRequest packet = new PacketChainConfigRequest(12, 345, TunnelDirectionSource.HIT_FACE);
        ByteBuf bytes = Unpooled.buffer();
        packet.toBytes(bytes);
        Assert.assertEquals(PacketChainConfigRequest.EXTENDED_PAYLOAD_BYTES, bytes.readableBytes());
        Assert.assertEquals(12, bytes.readInt());
        Assert.assertEquals(345, bytes.readInt());
        Assert.assertEquals(PacketChainConfigRequest.PROTOCOL_VERSION, bytes.readInt());
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE.wireCode(), bytes.readInt());
    }

    @Test
    public void oldClientToNewServerDecodesEightBytesAsLook() {
        ByteBuf legacy = Unpooled.buffer();
        legacy.writeInt(7).writeInt(99);
        PacketChainConfigRequest decoded = new PacketChainConfigRequest();
        decoded.fromBytes(legacy);
        Assert.assertTrue(decoded.rawValid);
        Assert.assertEquals(PacketChainConfigRequest.LEGACY_PROTOCOL_VERSION, decoded.protocolVersion);
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION.wireCode(), decoded.tunnelDirectionCode);
    }

    @Test
    public void s2cExtendedIsTwentyBytesAndKeepsLegacyPrefix() {
        PacketChainConfigSync packet = new PacketChainConfigSync(12, 345, 67, TunnelDirectionSource.HIT_FACE);
        ByteBuf bytes = Unpooled.buffer();
        packet.toBytes(bytes);
        Assert.assertEquals(PacketChainConfigSync.EXTENDED_PAYLOAD_BYTES, bytes.readableBytes());
        Assert.assertEquals(12, bytes.readInt());
        Assert.assertEquals(345, bytes.readInt());
        Assert.assertEquals(67, bytes.readInt());
        Assert.assertEquals(PacketChainConfigSync.PROTOCOL_VERSION, bytes.readInt());
        Assert.assertEquals(TunnelDirectionSource.HIT_FACE.wireCode(), bytes.readInt());
    }

    @Test
    public void oldServerToNewClientDecodesTwelveBytesAsLook() {
        ByteBuf legacy = Unpooled.buffer();
        legacy.writeInt(7).writeInt(99).writeInt(3);
        PacketChainConfigSync decoded = new PacketChainConfigSync();
        decoded.fromBytes(legacy);
        Assert.assertTrue(decoded.rawValid);
        Assert.assertEquals(PacketChainConfigSync.LEGACY_PROTOCOL_VERSION, decoded.protocolVersion);
        Assert.assertEquals(TunnelDirectionSource.LOOK_DIRECTION.wireCode(), decoded.tunnelDirectionCode);
    }

    @Test
    public void c2sRejectsTruncatedIntermediateAndTrailingLengthsWithoutThrowing() {
        for (int length : new int[] {0, 4, 7, 9, 12, 15, 17}) {
            PacketChainConfigRequest decoded = new PacketChainConfigRequest();
            decoded.fromBytes(Unpooled.buffer(length).writeZero(length));
            Assert.assertFalse("length=" + length, decoded.rawValid);
        }
    }

    @Test
    public void s2cRejectsTruncatedIntermediateAndTrailingLengthsWithoutThrowing() {
        for (int length : new int[] {0, 4, 11, 13, 16, 19, 21}) {
            PacketChainConfigSync decoded = new PacketChainConfigSync();
            decoded.fromBytes(Unpooled.buffer(length).writeZero(length));
            Assert.assertFalse("length=" + length, decoded.rawValid);
        }
    }
}
