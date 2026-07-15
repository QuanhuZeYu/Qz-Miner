package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/** 自动工具换位 round 建立 C2S 固定帧。 */
public final class PacketAutoToolSwapRoundStart implements IMessage {

    public static final int FIXED_PAYLOAD_BYTES = 4 + 8;

    public int protocolVersion;
    public long clientNonce;
    private boolean rawValid;

    /** FML 反序列化构造。 */
    public PacketAutoToolSwapRoundStart() {
    }

    /** 创建完整 round 建立请求。 */
    public PacketAutoToolSwapRoundStart(long clientNonce) {
        this(AutoToolSwapProtocol.PROTOCOL_VERSION, clientNonce);
    }

    /** 创建指定原始版本的 round 建立请求。 */
    public PacketAutoToolSwapRoundStart(int protocolVersion, long clientNonce) {
        this.protocolVersion = protocolVersion;
        this.clientNonce = clientNonce;
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
            clientNonce = buf.readLong();
            rawValid = true;
        } catch (IndexOutOfBoundsException error) {
            rawValid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(protocolVersion);
        buf.writeLong(clientNonce);
    }

    /** @return 是否完整捕获了固定长度原始字段。 */
    public boolean isRawValid() {
        return rawValid;
    }

    /** Netty 线程只捕获原始值和连接 identity，语义留给主线程 dispatcher。 */
    public static final class Handler implements IMessageHandler<PacketAutoToolSwapRoundStart, IMessage> {
        @Override
        public IMessage onMessage(PacketAutoToolSwapRoundStart message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerAutoToolSwapRequestDispatch.submitRoundStart(
                    player,
                    message == null ? 0 : message.protocolVersion,
                    message == null ? 0L : message.clientNonce,
                    message != null && message.rawValid);
            return null;
        }
    }
}
