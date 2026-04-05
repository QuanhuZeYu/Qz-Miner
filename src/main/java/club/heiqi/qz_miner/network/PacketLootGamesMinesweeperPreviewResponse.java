package club.heiqi.qz_miner.network;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.ClientProxy;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.client.FMLClientHandler;
import io.netty.buffer.ByteBuf;

/**
 * LootGames 扫雷预览结果响应包。
 */
public class PacketLootGamesMinesweeperPreviewResponse implements IMessage {

    public int requestId;
    public int originX;
    public int originY;
    public int originZ;
    public final List<ChainTarget> targets = new ArrayList<ChainTarget>();

    public PacketLootGamesMinesweeperPreviewResponse() {}

    public PacketLootGamesMinesweeperPreviewResponse(int requestId, ChainTarget origin, List<ChainTarget> targets) {
        this.requestId = requestId;
        this.originX = origin.getX();
        this.originY = origin.getY();
        this.originZ = origin.getZ();
        if (targets != null) {
            this.targets.addAll(targets);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        requestId = buf.readInt();
        originX = buf.readInt();
        originY = buf.readInt();
        originZ = buf.readInt();
        targets.clear();
        int size = buf.readInt();
        for (int i = 0; i < size; i++) {
            targets.add(new ChainTarget(buf.readInt(), buf.readInt(), buf.readInt()));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeInt(originX);
        buf.writeInt(originY);
        buf.writeInt(originZ);
        buf.writeInt(targets.size());
        for (ChainTarget target : targets) {
            buf.writeInt(target.getX());
            buf.writeInt(target.getY());
            buf.writeInt(target.getZ());
        }
    }

    /**
     * 客户端应用扫雷预览结果。
     */
    public static class Handler implements IMessageHandler<PacketLootGamesMinesweeperPreviewResponse, IMessage> {

        @Override
        public IMessage onMessage(final PacketLootGamesMinesweeperPreviewResponse message, MessageContext ctx) {
            FMLClientHandler.instance().getClient().func_152344_a(new Runnable() {

                @Override
                public void run() {
                    if (ClientProxy.chainPreviewController == null) {
                        return;
                    }

                    ClientProxy.chainPreviewController.applyLootGamesMinesweeperPreview(
                        message.requestId,
                        new ChainTarget(message.originX, message.originY, message.originZ),
                        message.targets);
                }
            });
            return null;
        }
    }
}
