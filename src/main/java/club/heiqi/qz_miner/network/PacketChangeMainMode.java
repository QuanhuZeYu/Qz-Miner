package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MineModeSelect.MainModeEnum;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

public class PacketChangeMainMode implements IMessage {
    public int mode;

    public PacketChangeMainMode() {
        mode = 0;
    }

    public PacketChangeMainMode(MainModeEnum clientMode) {
        mode = clientMode.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        mode = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(mode);
    }

    public static class Handler implements IMessageHandler<PacketChangeMainMode, IMessage> {
        @Override
        public IMessage onMessage(PacketChangeMainMode message, MessageContext ctx) {
            if(ctx.side.isServer()) {
                UUID uuid = ctx.getServerHandler().playerEntity.getUniqueID();
                AllPlayerStatue.getStatue(uuid).currentMainMode = message.mode;
                AllPlayerStatue.getStatue(uuid).mainMode = MainModeEnum.getMode(message.mode);
            }
            return null;
        }
    }
}
