package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.network.ServerChainConfigRequestValidator.Result;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
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
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final int requestedChainRadius = message.requestedChainRadius;
            final int requestedChainMaxBlocks = message.requestedChainMaxBlocks;
            ServerMainThreadDispatcher.tryRun(() -> {
                if (MyMod.chainStateService == null || player == null) {
                    return;
                }
                Result validated = ServerChainConfigRequestValidator.validateAndClamp(
                        requestedChainRadius,
                        requestedChainMaxBlocks,
                        Config.chainRadius,
                        Config.chainMaxBlocks);
                if (!validated.accepted) {
                    MyMod.LOG.warn("[ChainConfig] Rejected invalid client request config for player {} radius={} maxBlocks={}",
                            player.getUniqueID(), Integer.valueOf(requestedChainRadius),
                            Integer.valueOf(requestedChainMaxBlocks));
                    return;
                }
                ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
                state.setRequestedChainRadius(validated.radius);
                state.setRequestedChainMaxBlocks(validated.maxBlocks);
                MyMod.LOG.debug("[ChainConfig] Received client request config for player {} radius={} maxBlocks={}",
                    player.getUniqueID(), state.getRequestedChainRadius(), state.getRequestedChainMaxBlocks());
            });
            return null;
        }
    }
}
