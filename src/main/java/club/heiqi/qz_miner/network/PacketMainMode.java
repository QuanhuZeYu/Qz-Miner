package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.minerMode.enums.MainMode;
import club.heiqi.qz_miner.minerMode.ModeManager;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;

import java.util.UUID;

import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

public class PacketMainMode implements IMessage {
    public int mode;

    public PacketMainMode() {
        mode = 0;
    }

    public PacketMainMode(MainMode mode) {
        this.mode = mode.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        mode = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(mode);
    }

    public static class Handler implements IMessageHandler<PacketMainMode, IMessage> {
        @Override
        public IMessage onMessage(PacketMainMode message, MessageContext ctx) {
            EntityPlayer player = ctx.getServerHandler().playerEntity;
            UUID uuid = player.getUniqueID();
            ModeManager modeManager = allPlayerStorage.allPlayer.get(uuid);
            modeManager.mainMode = MainMode.values()[message.mode];
            return null;
        }
    }
}
