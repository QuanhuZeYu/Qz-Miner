package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.MinerModeState;
import club.heiqi.qz_miner.core.Manager;
import club.heiqi.qz_miner.utils.IMath;
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
        state.mainMode = IMath.clamp(buf.readInt(), 0, MinerModeState.MAIN_MODE.length);
        state.rangeMode = IMath.clamp(buf.readInt(), 0, MinerModeState.RANGE_MODE.length);
        state.chainMode = IMath.clamp(buf.readInt(), 0, MinerModeState.CHAIN_MODE.length);
        state.interactMode = IMath.clamp(buf.readInt(), 0, MinerModeState.INTERACT_MODE.length);
    }

    public void toBytes(ByteBuf buf) {
        buf.writeInt(state.mainMode);
        buf.writeInt(state.rangeMode);
        buf.writeInt(state.chainMode);
        buf.writeInt(state.interactMode);
    }

    public static class PacketMinerModeStateHandler implements IMessageHandler<PacketMinerModeState, IMessage> {
        public IMessage onMessage(PacketMinerModeState message, MessageContext ctx) {
            if (ctx.side.isServer()) {
                EntityPlayerMP playerMP = ctx.getServerHandler().playerEntity;
                Manager manager = MyMod.playerManager.managers.get(playerMP.getUniqueID());
                manager.minerModeState = message.state;
            }
            return null;
        }
    }
}
