package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/**
 * 服务端连锁状态同步包。
 */
public class PacketChainStateSync implements IMessage {

    public boolean chainKeyPressed;
    public boolean executing;
    public int modeOrdinal;
    public int executionStatusOrdinal;

    public PacketChainStateSync() {}

    public PacketChainStateSync(boolean chainKeyPressed, boolean executing, ChainMode mode, ChainExecutionStatus executionStatus) {
        this.chainKeyPressed = chainKeyPressed;
        this.executing = executing;
        this.modeOrdinal = mode.ordinal();
        this.executionStatusOrdinal = executionStatus.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        chainKeyPressed = buf.readBoolean();
        executing = buf.readBoolean();
        modeOrdinal = buf.readInt();
        executionStatusOrdinal = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(chainKeyPressed);
        buf.writeBoolean(executing);
        buf.writeInt(modeOrdinal);
        buf.writeInt(executionStatusOrdinal);
    }

    @SideOnly(Side.CLIENT)
    public static class Handler implements IMessageHandler<PacketChainStateSync, IMessage> {

        @Override
        public IMessage onMessage(PacketChainStateSync message, MessageContext ctx) {
            if (MyMod.chainStateService == null) {
                return null;
            }

            ChainMode[] modes = ChainMode.values();
            ChainMode mode = message.modeOrdinal >= 0 && message.modeOrdinal < modes.length
                ? modes[message.modeOrdinal]
                : ChainMode.CHAIN;
            ChainExecutionStatus[] statuses = ChainExecutionStatus.values();
            ChainExecutionStatus executionStatus = message.executionStatusOrdinal >= 0 && message.executionStatusOrdinal < statuses.length
                ? statuses[message.executionStatusOrdinal]
                : ChainExecutionStatus.IDLE;

            MyMod.chainStateService.getClientState().setServerChainKeyPressed(message.chainKeyPressed);
            MyMod.chainStateService.getClientState().setServerExecuting(message.executing);
            MyMod.chainStateService.getClientState().setServerExecutionStatus(executionStatus);
            MyMod.chainStateService.getClientState().setSelectedMode(mode);
            MyMod.LOG.debug("[ChainSync] Received chain state sync: pressed={}, executing={}, mode={}, status={}",
                message.chainKeyPressed, message.executing, mode, executionStatus);
            return null;
        }
    }
}
