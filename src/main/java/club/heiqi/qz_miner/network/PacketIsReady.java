package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.minerModes.ModeManager;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

public class PacketIsReady implements IMessage {
    public boolean isReady;

    public PacketIsReady() {
        isReady = false;
    }

    public PacketIsReady(boolean isReady) {
        this.isReady = isReady;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        isReady = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(isReady);
    }

    public static class Handler implements IMessageHandler<PacketIsReady, IMessage> {
        @Override
        public IMessage onMessage(PacketIsReady message, MessageContext ctx) {
            UUID uuid = ctx.getServerHandler().playerEntity.getUniqueID();
            ModeManager manager = allPlayerStorage.playerStatueMap.get(uuid).modeManager;
            manager.setIsReady(message.isReady);
            return null;
        }
    }
}
