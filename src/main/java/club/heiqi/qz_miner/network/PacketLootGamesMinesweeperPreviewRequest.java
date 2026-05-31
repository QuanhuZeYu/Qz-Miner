package club.heiqi.qz_miner.network;

import java.util.List;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * LootGames 扫雷预览请求包。
 */
public class PacketLootGamesMinesweeperPreviewRequest implements IMessage {

    public int requestId;
    public int targetX;
    public int targetY;
    public int targetZ;
    public int radius;
    public int maxTargets;

    public PacketLootGamesMinesweeperPreviewRequest() {}

    public PacketLootGamesMinesweeperPreviewRequest(int requestId, ChainTarget target, int radius, int maxTargets) {
        this.requestId = requestId;
        this.targetX = target.getX();
        this.targetY = target.getY();
        this.targetZ = target.getZ();
        this.radius = radius;
        this.maxTargets = maxTargets;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        requestId = buf.readInt();
        targetX = buf.readInt();
        targetY = buf.readInt();
        targetZ = buf.readInt();
        radius = buf.readInt();
        maxTargets = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeInt(targetX);
        buf.writeInt(targetY);
        buf.writeInt(targetZ);
        buf.writeInt(radius);
        buf.writeInt(maxTargets);
    }

    /**
     * 服务端处理扫雷预览请求。
     */
    public static class Handler implements IMessageHandler<PacketLootGamesMinesweeperPreviewRequest, IMessage> {

        @Override
        public IMessage onMessage(final PacketLootGamesMinesweeperPreviewRequest message, final MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final int requestId = message.requestId;
            final int targetX = message.targetX;
            final int targetY = message.targetY;
            final int targetZ = message.targetZ;
            final int radius = message.radius;
            final int maxTargets = message.maxTargets;
            ServerMainThreadDispatcher.run(() -> {
                if (MyMod.networkMain == null || player == null || player.worldObj == null) {
                    return;
                }

                ChainTarget target = new ChainTarget(targetX, targetY, targetZ);
                int requestedRadius = Math.max(1, Math.min(Config.chainRadius, radius));
                int requestedMaxTargets = Math.max(1, Math.min(Config.chainMaxBlocks, maxTargets));
                List<ChainTarget> bombs = CompatAdapters.minesweeper().collectBombTargets(
                    player.worldObj,
                    target,
                    requestedRadius,
                    requestedMaxTargets);
                MyMod.networkMain.network.sendTo(new PacketLootGamesMinesweeperPreviewResponse(requestId, target, bombs), player);
            });
            return null;
        }
    }
}
