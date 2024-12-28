package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.statueStorage.SelfStatue;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class PacketIsRunning implements IMessage {
    public boolean isRunning;

    public PacketIsRunning() {
        isRunning = false;
    }

    public PacketIsRunning(boolean isRunning) {
        this.isRunning = isRunning;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        isRunning = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(isRunning);
    }

    public static class Handler implements IMessageHandler<PacketIsRunning, IMessage> {
        @Override
        public IMessage onMessage(PacketIsRunning message, MessageContext ctx) {

            ModeManager manager = SelfStatue.modeManager;
            manager.isRunning.set(message.isRunning);
//            logger.info("玩家 {} 准备状态已切换为: {}", ctx.getServerHandler().playerEntity.getUniqueID(), manager.getIsReady());
            return null;
        }
    }
}
