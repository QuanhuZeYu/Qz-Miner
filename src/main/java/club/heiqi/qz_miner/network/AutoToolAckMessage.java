package club.heiqi.qz_miner.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** 未注册的阶段 1 S2C 自动工具定长 ACK DTO。 */
public final class AutoToolAckMessage implements IMessage {
    public static final int VERSION = 1, FIXED_PAYLOAD_BYTES = 3;
    public enum Result { APPLIED, RESTORED, CONFLICT, REJECTED }
    public int version, result, slot; private boolean valid;
    public AutoToolAckMessage() { }
    public AutoToolAckMessage(Result result, int slot) { version = VERSION; this.result = result.ordinal(); this.slot = slot; valid = true; }
    public void fromBytes(ByteBuf buf) { valid = false; if (buf == null || buf.readableBytes() != FIXED_PAYLOAD_BYTES) return;
        version = buf.readUnsignedByte(); result = buf.readUnsignedByte(); slot = buf.readUnsignedByte();
        valid = version == VERSION && result < Result.values().length && slot <= 35; }
    public void toBytes(ByteBuf buf) { buf.writeByte(version); buf.writeByte(result); buf.writeByte(slot); }
    public boolean isValid() { return valid; }
}
