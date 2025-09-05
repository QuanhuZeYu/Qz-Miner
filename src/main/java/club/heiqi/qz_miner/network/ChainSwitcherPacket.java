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

public class ChainSwitcherPacket implements IMessage {
    public boolean inChain;
    public ChainSwitcherPacket() {
        inChain = false;
    }
    public ChainSwitcherPacket(boolean inChain) {
        this.inChain = inChain;
    }
    /**
     * Convert from the supplied buffer into your specific message type
     *
     * @param buf
     */
    @Override
    public void fromBytes(ByteBuf buf) {
        inChain = buf.readBoolean();
    }

    /**
     * Deconstruct your message into the supplied byte buffer
     *
     * @param buf
     */
    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(inChain);
    }


    public static class ChainSwitcherPacketHandler implements IMessageHandler<ChainSwitcherPacket, IMessage> {
        public Logger LOG = LogManager.getLogger();

        /**
         * Called when a message is received of the appropriate type. You can optionally return a reply message, or null if no reply
         * is needed.
         *
         * @param message The message
         * @param ctx
         * @return an optional return message
         */
        @Override
        public IMessage onMessage(ChainSwitcherPacket message, MessageContext ctx) {
            if (ctx.side.isServer()) {
                 LOG.info("Player: {}Server: {}",
                         ctx.getServerHandler().playerEntity.getDisplayName(),
                         message.inChain ? "按下连锁键" : "松开连锁键"
                 );
                EntityPlayerMP playerMP = ctx.getServerHandler().playerEntity;
                Manager manager = MyMod.playerManager.managers.get(playerMP);
                manager.inPressChainKey = message.inChain;
            }
            return null;
        }
    }
}
