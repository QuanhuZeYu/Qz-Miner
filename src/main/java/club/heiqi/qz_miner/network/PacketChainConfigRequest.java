package club.heiqi.qz_miner.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端向服务端提交建议连锁参数。
 *
 * <p>Handler 只捕获原始 int 与端点身份，经 {@link ServerChainConfigRequestDispatch}
 * 进入 keyed latest-wins 泳道；不直接每包入普通 FIFO。</p>
 */
public class PacketChainConfigRequest implements IMessage {

    public int requestedChainRadius;
    public int requestedChainMaxBlocks;

    public PacketChainConfigRequest() {}

    public PacketChainConfigRequest(int requestedChainRadius, int requestedChainMaxBlocks) {
        this.requestedChainRadius = requestedChainRadius;
        this.requestedChainMaxBlocks = requestedChainMaxBlocks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        requestedChainRadius = buf.readInt();
        requestedChainMaxBlocks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(requestedChainRadius);
        buf.writeInt(requestedChainMaxBlocks);
    }

    /**
     * 服务端记录客户端建议参数。
     */
    public static class Handler implements IMessageHandler<PacketChainConfigRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketChainConfigRequest message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final int requestedChainRadius = message.requestedChainRadius;
            final int requestedChainMaxBlocks = message.requestedChainMaxBlocks;
            // 守 I4：只捕获原始数据与端点身份；校验/写入在 keyed lane 的服务端主线程消费中完成
            ServerChainConfigRequestDispatch.submit(player, requestedChainRadius, requestedChainMaxBlocks);
            return null;
        }
    }
}
