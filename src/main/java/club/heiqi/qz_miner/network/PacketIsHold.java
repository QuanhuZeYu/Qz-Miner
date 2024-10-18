package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;

import java.util.UUID;

public class PacketIsHold implements IMessage {
    public boolean isHold = false;

    public PacketIsHold() {
        isHold = false;
    }

    public PacketIsHold(boolean isHold) {
        this.isHold = isHold;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        isHold = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(isHold);
    }

    public static class Handler implements IMessageHandler<PacketIsHold, IMessage> {
        // 仅客户端向服务端发送的包
        @Override
        public IMessage onMessage(PacketIsHold message, MessageContext ctx) {
            UUID uuid = ctx.getServerHandler().playerEntity.getUniqueID();
            AllPlayerStatue.getStatue(uuid).minerIsOpen = message.isHold;
            return null;
        }
    }
}
