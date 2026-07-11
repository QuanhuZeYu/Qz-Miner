package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端确认对象组配置的请求 revision、权威 revision 和接受状态。
 *
 * <p>协议固定为 25 字节：protocolVersion、requestedRevision、
 * authoritativeRevision、accepted byte 和 groupCount。{@code fromBytes} 只捕获
 * 原始值及结构长度结果；协议语义由客户端主线程验证，且不持有 Forge ByteBuf。</p>
 */
public final class PacketObjectGroupConfigSync implements IMessage {

    public static final int FIXED_PAYLOAD_BYTES = 4 + 8 + 8 + 1 + 4;

    public int protocolVersion;
    public long requestedRevision;
    public long authoritativeRevision;
    public int acceptedFlag;
    public int groupCount;
    private boolean rawValid;

    public PacketObjectGroupConfigSync() {
    }

    public PacketObjectGroupConfigSync(
            int protocolVersion, long requestedRevision, long authoritativeRevision,
            boolean accepted, int groupCount) {
        this.protocolVersion = protocolVersion;
        this.requestedRevision = requestedRevision;
        this.authoritativeRevision = authoritativeRevision;
        this.acceptedFlag = accepted ? 1 : 0;
        this.groupCount = groupCount;
        this.rawValid = true;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        rawValid = false;
        if (buf == null || buf.readableBytes() != FIXED_PAYLOAD_BYTES) {
            return;
        }
        try {
            protocolVersion = buf.readInt();
            requestedRevision = buf.readLong();
            authoritativeRevision = buf.readLong();
            acceptedFlag = buf.readUnsignedByte();
            groupCount = buf.readInt();
            rawValid = true;
        } catch (IndexOutOfBoundsException e) {
            // 固定长度预检后仍以 invalid 结束，不能让畸形包穿透 Handler。
            rawValid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(protocolVersion);
        buf.writeLong(requestedRevision);
        buf.writeLong(authoritativeRevision);
        buf.writeByte(acceptedFlag);
        buf.writeInt(groupCount);
    }

    /**
     * @return 是否完整捕获了固定长度原始字段；不代表协议语义合法
     */
    public boolean isRawValid() {
        return rawValid;
    }

    /** Handler 只转交原始值、rawValid 与 common connection identity。 */
    public static final class Handler implements IMessageHandler<PacketObjectGroupConfigSync, IMessage> {
        @Override
        public IMessage onMessage(PacketObjectGroupConfigSync message, MessageContext ctx) {
            final PacketObjectGroupConfigSync captured = message;
            MyMod.proxy.handleClientObjectGroupConfigSync(
                    captured == null ? 0 : captured.protocolVersion,
                    captured == null ? 0L : captured.requestedRevision,
                    captured == null ? 0L : captured.authoritativeRevision,
                    captured == null ? -1 : captured.acceptedFlag,
                    captured == null ? 0 : captured.groupCount,
                    captured != null && captured.rawValid,
                    ctx.netHandler);
            return null;
        }
    }
}
