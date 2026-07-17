package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.network.ServerObjectGroupConfigRequestDispatch;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端向服务端提交完整对象组规则集；与旧半径配置包独立。
 */
public final class PacketObjectGroupConfigRequest implements IMessage {

    private ObjectGroupWireConfig payload;

    public PacketObjectGroupConfigRequest() {
    }

    public PacketObjectGroupConfigRequest(ObjectGroupWireConfig payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        this.payload = payload;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        payload = ObjectGroupWireConfig.read(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        if (payload != null) {
            payload.write(buf);
        }
    }

    /** 测试和 Handler 使用的只读 raw payload。 */
    public ObjectGroupWireConfig payload() {
        return payload;
    }

    /** Netty 线程只捕获端点和有界 raw payload。 */
    public static final class Handler implements IMessageHandler<PacketObjectGroupConfigRequest, IMessage> {
        @Override
        public IMessage onMessage(PacketObjectGroupConfigRequest message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final ObjectGroupWireConfig captured = message == null ? null : message.payload;
            ServerObjectGroupConfigRequestDispatch.submit(player, captured);
            return null;
        }
    }
}
