package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.minerMode.ModeManager;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;

import java.util.UUID;

import static club.heiqi.qz_miner.Mod_Main.allPlayerStorage;

public class PacketPrintResult implements IMessage {
    public boolean printResult;

    public PacketPrintResult() {
        printResult = true;
    }
    public PacketPrintResult(boolean printResult) {
        this.printResult = printResult;
    }
    @Override
    public void fromBytes(ByteBuf buf) {
        printResult = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(printResult);
    }

    public static class Handler implements IMessageHandler<PacketPrintResult, IMessage> {
        @Override
        public IMessage onMessage(PacketPrintResult message, MessageContext ctx) {
            EntityPlayer player = ctx.getServerHandler().playerEntity;
            UUID uuid = player.getUniqueID();
            ModeManager modeManager = allPlayerStorage.allPlayer.get(uuid);
            modeManager.setPrintResult(message.printResult);
            return null;
        }
    }
}
