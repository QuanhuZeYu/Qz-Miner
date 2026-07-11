package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 对象组 S2C 固定 framing 与原始字段捕获回归。 */
public class PacketObjectGroupConfigSyncTest {

    @Test
    public void roundTripKeepsRequestedAndAuthoritativeRevisions() {
        PacketObjectGroupConfigSync source = new PacketObjectGroupConfigSync(1, 5L, 5L, true, 3);
        ByteBuf buffer = Unpooled.buffer(PacketObjectGroupConfigSync.FIXED_PAYLOAD_BYTES);
        source.toBytes(buffer);

        PacketObjectGroupConfigSync decoded = new PacketObjectGroupConfigSync();
        decoded.fromBytes(buffer);

        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(5L, decoded.requestedRevision);
        Assert.assertEquals(5L, decoded.authoritativeRevision);
        Assert.assertEquals(1, decoded.acceptedFlag);
        Assert.assertEquals(3, decoded.groupCount);
    }

    @Test
    public void truncatedAndTrailingPacketsAreInvalidWithoutThrowing() {
        PacketObjectGroupConfigSync source = new PacketObjectGroupConfigSync(1, 5L, 4L, false, 0);
        ByteBuf truncated = Unpooled.buffer(PacketObjectGroupConfigSync.FIXED_PAYLOAD_BYTES);
        source.toBytes(truncated);
        truncated.writerIndex(truncated.writerIndex() - 1);
        PacketObjectGroupConfigSync truncatedPacket = new PacketObjectGroupConfigSync();
        truncatedPacket.fromBytes(truncated);
        Assert.assertFalse(truncatedPacket.isRawValid());

        ByteBuf trailing = Unpooled.buffer(PacketObjectGroupConfigSync.FIXED_PAYLOAD_BYTES + 1);
        source.toBytes(trailing);
        trailing.writeByte(0);
        PacketObjectGroupConfigSync trailingPacket = new PacketObjectGroupConfigSync();
        trailingPacket.fromBytes(trailing);
        Assert.assertFalse(trailingPacket.isRawValid());
    }

    @Test
    public void framingAcceptsRawBooleanForMainThreadSemanticValidation() {
        ByteBuf buffer = Unpooled.buffer(PacketObjectGroupConfigSync.FIXED_PAYLOAD_BYTES);
        buffer.writeInt(1);
        buffer.writeLong(5L);
        buffer.writeLong(4L);
        buffer.writeByte(2);
        buffer.writeInt(0);

        PacketObjectGroupConfigSync decoded = new PacketObjectGroupConfigSync();
        decoded.fromBytes(buffer);

        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(2, decoded.acceptedFlag);
    }
}
