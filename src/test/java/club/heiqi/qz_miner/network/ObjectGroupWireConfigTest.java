package club.heiqi.qz_miner.network;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;
import club.heiqi.qz_miner.objectgroup.ObjectGroupParser;
import club.heiqi.qz_miner.objectgroup.ObjectGroupRuleSet;
import club.heiqi.qz_miner.objectgroup.ObjectGroupSelector;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** 对象组 wire 的协议、上下限和截断回归。 */
public class ObjectGroupWireConfigTest {

    @Test
    public void roundTripKeepsFullStructuredMembers() {
        ObjectGroupRuleSet rules = new ObjectGroupRuleSet(Collections.singletonList(
                new ObjectGroup("logs", Arrays.asList(
                        ObjectGroupParser.parseSelector("minecraft:log@0"),
                        ObjectGroupParser.parseSelector("minecraft:log@[4,8,12]")))));
        ObjectGroupWireConfig source = ObjectGroupWireConfig.fromRuleSet(7L, rules);
        ByteBuf buf = Unpooled.buffer(source.encodedSize());
        source.write(buf);
        ObjectGroupWireConfig decoded = ObjectGroupWireConfig.read(buf);

        Assert.assertTrue(decoded.isValid());
        Assert.assertEquals(ObjectGroupWireConfig.PROTOCOL_VERSION, decoded.protocolVersion());
        Assert.assertEquals(7L, decoded.revision());
        Assert.assertEquals("minecraft:log@[4,8,12]", decoded.groups().get(0).members().get(1));
    }

    @Test
    public void oversizedAndTruncatedPayloadsAreInvalid() {
        ByteBuf oversized = Unpooled.buffer(ObjectGroupWireConfig.MAX_PAYLOAD_BYTES + 1);
        oversized.writeZero(ObjectGroupWireConfig.MAX_PAYLOAD_BYTES + 1);
        Assert.assertFalse(ObjectGroupWireConfig.read(oversized).isValid());

        ObjectGroupWireConfig source = ObjectGroupWireConfig.fromRuleSet(1L, ObjectGroupRuleSet.EMPTY);
        ByteBuf truncated = Unpooled.buffer(source.encodedSize());
        source.write(truncated);
        truncated.writerIndex(truncated.writerIndex() - 1);
        Assert.assertFalse(ObjectGroupWireConfig.read(truncated).isValid());
    }

    @Test
    public void protocolAndCountsRemainBoundedAtWireBoundary() {
        ObjectGroupWireConfig source = ObjectGroupWireConfig.fromRuleSet(2L, ObjectGroupRuleSet.EMPTY);
        ByteBuf badVersion = Unpooled.buffer(source.encodedSize());
        source.write(badVersion);
        badVersion.setInt(0, ObjectGroupWireConfig.PROTOCOL_VERSION + 1);
        ObjectGroupWireConfig decoded = ObjectGroupWireConfig.read(badVersion);
        Assert.assertTrue(decoded.isValid());
        Assert.assertEquals(ObjectGroupWireConfig.PROTOCOL_VERSION + 1, decoded.protocolVersion());

        ByteBuf badCount = Unpooled.buffer();
        badCount.writeInt(ObjectGroupWireConfig.PROTOCOL_VERSION);
        badCount.writeLong(3L);
        badCount.writeInt(ObjectGroupWireConfig.MAX_GROUPS + 1);
        Assert.assertFalse(ObjectGroupWireConfig.read(badCount).isValid());
    }
}
