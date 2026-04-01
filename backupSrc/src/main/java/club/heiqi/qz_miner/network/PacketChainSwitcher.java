package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.Manager;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class PacketChainSwitcher implements IMessage {
    public boolean inChain;
    public PacketChainSwitcher() {
        inChain = false;
    }
    public PacketChainSwitcher(boolean inChain) {
        this.inChain = inChain;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        inChain = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(inChain);
    }


    public static class ChainSwitcherPacketHandler implements IMessageHandler<PacketChainSwitcher, IMessage> {
        public Logger LOG = LogManager.getLogger();

        @Override
        public IMessage onMessage(PacketChainSwitcher message, MessageContext ctx) {
            if (ctx.side.isServer()) {
                EntityPlayerMP playerMP = ctx.getServerHandler().playerEntity;
                Manager manager = MyMod.playerManager.managers.get(playerMP.getUniqueID());
                manager.inPressChainKey = message.inChain;
            }
            return null;
        }
    }
}
