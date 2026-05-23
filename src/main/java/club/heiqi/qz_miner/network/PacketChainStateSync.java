package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 服务端连锁状态同步包。
 */
public class PacketChainStateSync implements IMessage {

    public boolean chainKeyPressed;
    public boolean executing;
    public int modeOrdinal;
    public int subModeOrdinal;
    public int executionStatusOrdinal;
    public int chainRadius;
    public int chainMaxBlocks;
    public int matchedTargetCount;

    public PacketChainStateSync() {}

    public PacketChainStateSync(boolean chainKeyPressed, boolean executing, ChainMode mode, ChainSubMode subMode, ChainExecutionStatus executionStatus, int chainRadius, int chainMaxBlocks, int matchedTargetCount) {
        this.chainKeyPressed = chainKeyPressed;
        this.executing = executing;
        this.modeOrdinal = mode.ordinal();
        this.subModeOrdinal = subMode == null ? -1 : subMode.ordinal();
        this.executionStatusOrdinal = executionStatus.ordinal();
        this.chainRadius = chainRadius;
        this.chainMaxBlocks = chainMaxBlocks;
        this.matchedTargetCount = matchedTargetCount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        chainKeyPressed = buf.readBoolean();
        executing = buf.readBoolean();
        modeOrdinal = buf.readInt();
        subModeOrdinal = buf.readInt();
        executionStatusOrdinal = buf.readInt();
        chainRadius = buf.readInt();
        chainMaxBlocks = buf.readInt();
        matchedTargetCount = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(chainKeyPressed);
        buf.writeBoolean(executing);
        buf.writeInt(modeOrdinal);
        buf.writeInt(subModeOrdinal);
        buf.writeInt(executionStatusOrdinal);
        buf.writeInt(chainRadius);
        buf.writeInt(chainMaxBlocks);
        buf.writeInt(matchedTargetCount);
    }

    public static class Handler implements IMessageHandler<PacketChainStateSync, IMessage> {

        @Override
        public IMessage onMessage(PacketChainStateSync message, MessageContext ctx) {
            ChainMode[] modes = ChainMode.values();
            ChainMode mode = message.modeOrdinal >= 0 && message.modeOrdinal < modes.length
                ? modes[message.modeOrdinal]
                : ChainMode.CHAIN;
            mode = ChainModeRegistry.resolveMode(mode);
            ChainSubMode[] subModes = ChainSubMode.values();
            ChainSubMode subMode = message.subModeOrdinal >= 0 && message.subModeOrdinal < subModes.length
                ? subModes[message.subModeOrdinal]
                : null;
            subMode = ChainModeRegistry.resolveSubMode(mode, subMode);
            ChainExecutionStatus[] statuses = ChainExecutionStatus.values();
            ChainExecutionStatus executionStatus = message.executionStatusOrdinal >= 0 && message.executionStatusOrdinal < statuses.length
                ? statuses[message.executionStatusOrdinal]
                : ChainExecutionStatus.IDLE;
            int chainRadius = message.chainRadius > 0 ? message.chainRadius : Config.chainRadius;
            int chainMaxBlocks = message.chainMaxBlocks > 0 ? message.chainMaxBlocks : Config.chainMaxBlocks;
            int matchedTargetCount = Math.max(0, message.matchedTargetCount);

            MyMod.proxy.handleClientChainStateSync(message.chainKeyPressed, message.executing, mode, subMode, executionStatus, chainRadius, chainMaxBlocks, matchedTargetCount);
            MyMod.LOG.debug("[ChainSync] Received chain state sync: pressed={}, executing={}, mode={}, subMode={}, status={}, chainRadius={}, chainMaxBlocks={}, matchedTargets={}",
                message.chainKeyPressed, message.executing, mode, subMode, executionStatus, chainRadius, chainMaxBlocks, matchedTargetCount);
            return null;
        }
    }
}
