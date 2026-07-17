package club.heiqi.qz_miner.network;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;

/** 接替包固定帧长度事实。 */
public class PacketAutoToolSwapTakeoverRequestTest {
    @Test
    public void fixedPayloadRemainsSixtyBytes() {
        Assert.assertEquals(60, PacketAutoToolSwapTakeoverRequest.FIXED_PAYLOAD_BYTES);
        Assert.assertEquals(3, AutoToolSwapProtocol.PROTOCOL_VERSION);
    }
}
