package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 接替包固定帧长度事实。 */
public class PacketAutoToolSwapTakeoverRequestTest {
    @Test
    public void fixedPayloadRemainsSixtyBytes() {
        Assert.assertEquals(60, PacketAutoToolSwapTakeoverRequest.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(3, AutoToolSwapProtocol.PROTOCOL_VERSION);
    }

    @Test
    public void fullIntTargetIdentityRoundTripsWithoutChangingFrame() {
        AutoToolSwapTakeoverRequest request = new AutoToolSwapTakeoverRequest(
                AutoToolSwapProtocol.PROTOCOL_VERSION, 7L, 3L, 4,
                1, 64, 2, Integer.MAX_VALUE, Integer.MAX_VALUE, 10L, 18L);
        PacketAutoToolSwapTakeoverRequest source = new PacketAutoToolSwapTakeoverRequest(request);
        ByteBuf buffer = Unpooled.buffer(PacketAutoToolSwapTakeoverRequest.FIXED_PAYLOAD_BYTES);

        source.toBytes(buffer);
        Assert.assertEquals(PacketAutoToolSwapTakeoverRequest.FIXED_PAYLOAD_BYTES, buffer.readableBytes());
        PacketAutoToolSwapTakeoverRequest decoded = new PacketAutoToolSwapTakeoverRequest();
        decoded.fromBytes(buffer);

        Assert.assertTrue(decoded.isRawValid());
        Assert.assertEquals(Integer.MAX_VALUE, decoded.targetBlockId);
        Assert.assertEquals(Integer.MAX_VALUE, decoded.targetBlockMetadata);
        Assert.assertFalse(buffer.isReadable());
    }
}
