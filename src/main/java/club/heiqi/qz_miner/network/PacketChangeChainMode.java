package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MineModeSelect.ChainModeEnum;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

public class PacketChangeChainMode implements IMessage {
    public int mode;

    public PacketChangeChainMode() {
        mode = 0;
    }

    public PacketChangeChainMode(ChainModeEnum clientMode) {
        switch (clientMode) {
            case RectangularMode:
                mode = 1;
                break;
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        mode = buf.readShort();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeShort(mode);
    }

    public static class Handler implements IMessageHandler<PacketChangeChainMode, IMessage> {
        @Override
        public IMessage onMessage(PacketChangeChainMode message, MessageContext ctx) {
            if(ctx.side.isServer()) {
                UUID uuid = ctx.getServerHandler().playerEntity.getUniqueID();
                AllPlayerStatue.getStatue(uuid).currentChainMode = message.mode;
            }
            return null;
        }
    }
}

