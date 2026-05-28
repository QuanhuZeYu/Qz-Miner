package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端向服务端提交建议连锁参数。
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
            if (MyMod.chainStateService == null) {
                return null;
            }

            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
            int requestedRadius = message.requestedChainRadius > 0
                ? Math.min(Config.chainRadius, message.requestedChainRadius)
                : -1;
            int requestedMaxBlocks = message.requestedChainMaxBlocks > 0
                ? Math.min(Config.chainMaxBlocks, message.requestedChainMaxBlocks)
                : -1;
            state.setRequestedChainRadius(requestedRadius);
            state.setRequestedChainMaxBlocks(requestedMaxBlocks);
            MyMod.LOG.debug("[ChainConfig] Received client request config for player {} radius={} maxBlocks={}",
                player.getUniqueID(), state.getRequestedChainRadius(), state.getRequestedChainMaxBlocks());
            return null;
        }
    }
}
