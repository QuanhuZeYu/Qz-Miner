package club.heiqi.qz_miner.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** 未注册的阶段 1 C2S 自动工具定长命令 DTO。 */
public final class AutoToolCommandMessage implements IMessage {
    public static final int VERSION = 1, FIXED_PAYLOAD_BYTES = 6;
    public enum Action { SELECT, RESTORE }
    public int version, action, slot; private boolean valid;
    public AutoToolCommandMessage() { }
    public AutoToolCommandMessage(Action action, int slot) { version = VERSION; this.action = action.ordinal(); this.slot = slot; valid = true; }
    public void fromBytes(ByteBuf buf) { valid = false; if (buf == null || buf.readableBytes() != FIXED_PAYLOAD_BYTES) return;
        version = buf.readUnsignedByte(); action = buf.readUnsignedByte(); slot = buf.readInt();
        valid = version == VERSION && action < Action.values().length && slot >= 0 && slot <= 35; }
    public void toBytes(ByteBuf buf) { buf.writeByte(version); buf.writeByte(action); buf.writeInt(slot); }
    public boolean isValid() { return valid; }
}
