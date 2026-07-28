package club.heiqi.qz_miner.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/** 客户端提交当前视线命中的一个框选点。 */
public final class PacketCuboidSelectionRequest implements IMessage {

    public static final int PROTOCOL_VERSION = 1;
    public static final int PAYLOAD_BYTES = 20;

    public int protocolVersion = PROTOCOL_VERSION;
    public int pointIndex;
    public int x;
    public int y;
    public int z;
    public boolean rawValid = true;

    public PacketCuboidSelectionRequest() {}

    public PacketCuboidSelectionRequest(int pointIndex, int x, int y, int z) {
        this.pointIndex = pointIndex;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        rawValid = buf.readableBytes() == PAYLOAD_BYTES;
        if (!rawValid) {
            buf.skipBytes(buf.readableBytes());
            return;
        }
        protocolVersion = buf.readInt();
        pointIndex = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(PROTOCOL_VERSION);
        buf.writeInt(pointIndex);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    /** Netty 线程只捕获纯值与端点，服务端主线程再验证当前射线和状态。 */
    public static final class Handler implements IMessageHandler<PacketCuboidSelectionRequest, IMessage> {
        @Override
        public IMessage onMessage(PacketCuboidSelectionRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            ServerCuboidSelectionRequestDispatch.submit(player, message.protocolVersion,
                    message.pointIndex, message.x, message.y, message.z, message.rawValid);
            return null;
        }
    }
}
