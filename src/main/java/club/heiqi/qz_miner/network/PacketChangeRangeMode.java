package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MineModeSelect.RangeModeEnum;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

public class PacketChangeRangeMode implements IMessage {
    public int mode;

    public PacketChangeRangeMode() {
        mode = 0;
    }

    public PacketChangeRangeMode(RangeModeEnum clientMode) {
        switch (clientMode) {
            case planarRestrictedMode:
                mode = 1;
                break;
            case centerRectangularMode:
                mode = 2;
                break;
            case centerMode:
            default:
                mode = 0;
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

    public static class Handler implements IMessageHandler<PacketChangeRangeMode, IMessage> {
        @Override
        public IMessage onMessage(PacketChangeRangeMode message, MessageContext ctx) {
            if(ctx.side.isServer()) {
                UUID uuid = ctx.getServerHandler().playerEntity.getUniqueID();
                AllPlayerStatue.getStatue(uuid).currentRangeMode = message.mode;
            }
            return null;
        }
    }
}

