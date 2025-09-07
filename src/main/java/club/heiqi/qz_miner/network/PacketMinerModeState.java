package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.MinerModeState;
import club.heiqi.qz_miner.core.Manager;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

public class PacketMinerModeState implements IMessage {
    public MinerModeState state;

    public PacketMinerModeState() {
        state = new MinerModeState();
    }
    public PacketMinerModeState(MinerModeState state) {
        this.state = state;
    }

    public void fromBytes(ByteBuf buf) {
        state.mainMode = buf.readInt();
        state.rangeMode = buf.readInt();
        state.chainMode = buf.readInt();
    }

    public void toBytes(ByteBuf buf) {
        buf.writeInt(state.mainMode);
        buf.writeInt(state.rangeMode);
        buf.writeInt(state.chainMode);
    }

    public static class PacketMinerModeStateHandler implements IMessageHandler<PacketMinerModeState, IMessage> {
        public IMessage onMessage(PacketMinerModeState message, MessageContext ctx) {
            if (ctx.side.isServer()) {
                EntityPlayerMP playerMP = ctx.getServerHandler().playerEntity;
                Manager manager = MyMod.playerManager.managers.get(playerMP);
                manager.minerModeState = message.state;
            }
            return null;
        }
    }
}
