package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁模式切换同步包。
 */
public class PacketChainModeSwitch implements IMessage {

    public int modeOrdinal;

    public PacketChainModeSwitch() {}

    public PacketChainModeSwitch(ChainMode mode) {
        this.modeOrdinal = mode.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        modeOrdinal = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(modeOrdinal);
    }

    public static class Handler implements IMessageHandler<PacketChainModeSwitch, IMessage> {

        @Override
        public IMessage onMessage(PacketChainModeSwitch message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final int modeOrdinal = message.modeOrdinal;
            ServerMainThreadDispatcher.run(() -> {
                if (MyMod.chainStateService == null || player == null) {
                    return;
                }

                ChainMode[] modes = ChainMode.values();
                ChainMode mode = modeOrdinal >= 0 && modeOrdinal < modes.length
                    ? modes[modeOrdinal]
                    : ChainMode.CHAIN;

                MyMod.chainStateService.setPlayerSelectedMode(player.getUniqueID(), mode);
            });
            return null;
        }
    }
}
