package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
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
            if (MyMod.chainStateService == null) {
                return null;
            }

            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ChainMode[] modes = ChainMode.values();
            ChainMode mode = message.modeOrdinal >= 0 && message.modeOrdinal < modes.length
                ? modes[message.modeOrdinal]
                : ChainMode.CHAIN;

            MyMod.chainStateService.setPlayerSelectedMode(player.getUniqueID(), mode);
            return null;
        }
    }
}
