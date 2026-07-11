package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端确认对象组配置的 revision 和接受状态。
 */
public final class PacketObjectGroupConfigSync implements IMessage {

    public int protocolVersion;
    public long revision;
    public boolean accepted;
    public int groupCount;

    public PacketObjectGroupConfigSync() {
    }

    public PacketObjectGroupConfigSync(int protocolVersion, long revision, boolean accepted, int groupCount) {
        this.protocolVersion = protocolVersion;
        this.revision = revision;
        this.accepted = accepted;
        this.groupCount = groupCount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocolVersion = buf.readInt();
        revision = buf.readLong();
        accepted = buf.readBoolean();
        groupCount = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(protocolVersion);
        buf.writeLong(revision);
        buf.writeBoolean(accepted);
        buf.writeInt(groupCount);
    }

    /** Handler 只转交纯原始值与 common connection identity。 */
    public static final class Handler implements IMessageHandler<PacketObjectGroupConfigSync, IMessage> {
        @Override
        public IMessage onMessage(PacketObjectGroupConfigSync message, MessageContext ctx) {
            if (message == null) {
                return null;
            }
            MyMod.proxy.handleClientObjectGroupConfigSync(
                    message.protocolVersion, message.revision, message.accepted,
                    message.groupCount, ctx.netHandler);
            return null;
        }
    }
}
