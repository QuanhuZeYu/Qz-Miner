package club.heiqi.qz_miner.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Assert;
import org.junit.Test;

/** 自动工具未注册消息的严格 framing/枚举测试。 */
public class AutoToolMessageCodecTest {
    @Test public void commandRoundTripsAndRejectsMalformedPackets() {
        ByteBuf valid = Unpooled.buffer(); new AutoToolCommandMessage(AutoToolCommandMessage.Action.SELECT, 35).toBytes(valid);
        AutoToolCommandMessage decoded = new AutoToolCommandMessage(); decoded.fromBytes(valid); Assert.assertTrue(decoded.isValid());
        assertInvalidCommand(bytes(1, 9, 0, 0, 0, 0)); assertInvalidCommand(bytes(2, 0, 0, 0, 0, 0));
        assertInvalidCommand(bytes(1, 0, 0, 0, 0)); assertInvalidCommand(bytes(1, 0, 0, 0, 0, 0, 0));
    }
    @Test public void ackRejectsIllegalVersionResultAndFraming() {
        ByteBuf valid = Unpooled.buffer(); new AutoToolAckMessage(AutoToolAckMessage.Result.APPLIED, 0).toBytes(valid);
        AutoToolAckMessage decoded = new AutoToolAckMessage(); decoded.fromBytes(valid); Assert.assertTrue(decoded.isValid());
        assertInvalidAck(bytes(1, 9, 0)); assertInvalidAck(bytes(2, 0, 0)); assertInvalidAck(bytes(1, 0)); assertInvalidAck(bytes(1,0,0,0));
    }
    private static void assertInvalidCommand(ByteBuf b){AutoToolCommandMessage m=new AutoToolCommandMessage();m.fromBytes(b);Assert.assertFalse(m.isValid());}
    private static void assertInvalidAck(ByteBuf b){AutoToolAckMessage m=new AutoToolAckMessage();m.fromBytes(b);Assert.assertFalse(m.isValid());}
    private static ByteBuf bytes(int... values){ByteBuf b=Unpooled.buffer();for(int value:values)b.writeByte(value);return b;}
}
