package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 自动工具同 round 接替目标请求 S2C 固定帧。 */
public final class PacketAutoToolSwapTakeoverRequest implements IMessage {

    public static final int FIXED_PAYLOAD_BYTES = 4 + 8 + 8 + 4 + 4 + 4 + 4 + 4 + 4 + 8 + 8;

    public int protocolVersion;
    public long serverRoundId;
    public long actionSequence;
    public int generation;
    public int targetX;
    public int targetY;
    public int targetZ;
    public int targetBlockId;
    public int targetBlockMetadata;
    public long serverTick;
    public long deadlineTick;
    private boolean rawValid;

    public PacketAutoToolSwapTakeoverRequest() {}

    /** 从不可变请求创建 wire 帧。 */
    public PacketAutoToolSwapTakeoverRequest(AutoToolSwapTakeoverRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        protocolVersion = request.protocolVersion();
        serverRoundId = request.serverRoundId();
        actionSequence = request.actionSequence();
        generation = request.generation();
        targetX = request.targetX();
        targetY = request.targetY();
        targetZ = request.targetZ();
        targetBlockId = request.targetBlockId();
        targetBlockMetadata = request.targetBlockMetadata();
        serverTick = request.serverTick();
        deadlineTick = request.deadlineTick();
        rawValid = true;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        rawValid = false;
        if (buf == null || buf.readableBytes() != FIXED_PAYLOAD_BYTES) return;
        try {
            protocolVersion = buf.readInt();
            serverRoundId = buf.readLong();
            actionSequence = buf.readLong();
            generation = buf.readInt();
            targetX = buf.readInt();
            targetY = buf.readInt();
            targetZ = buf.readInt();
            targetBlockId = buf.readInt();
            targetBlockMetadata = buf.readInt();
            serverTick = buf.readLong();
            deadlineTick = buf.readLong();
            rawValid = true;
        } catch (IndexOutOfBoundsException truncated) {
            rawValid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(protocolVersion);
        buf.writeLong(serverRoundId);
        buf.writeLong(actionSequence);
        buf.writeInt(generation);
        buf.writeInt(targetX);
        buf.writeInt(targetY);
        buf.writeInt(targetZ);
        buf.writeInt(targetBlockId);
        buf.writeInt(targetBlockMetadata);
        buf.writeLong(serverTick);
        buf.writeLong(deadlineTick);
    }

    public boolean isRawValid() { return rawValid; }

    /** Netty 只捕获原始字段并转交 common proxy。 */
    public static final class Handler implements IMessageHandler<PacketAutoToolSwapTakeoverRequest, IMessage> {
        @Override
        public IMessage onMessage(PacketAutoToolSwapTakeoverRequest message, MessageContext ctx) {
            MyMod.proxy.handleClientAutoToolSwapTakeoverRequest(
                    message == null ? 0 : message.protocolVersion,
                    message == null ? 0L : message.serverRoundId,
                    message == null ? 0L : message.actionSequence,
                    message == null ? 0 : message.generation,
                    message == null ? 0 : message.targetX,
                    message == null ? 0 : message.targetY,
                    message == null ? 0 : message.targetZ,
                    message == null ? 0 : message.targetBlockId,
                    message == null ? 0 : message.targetBlockMetadata,
                    message == null ? 0L : message.serverTick,
                    message == null ? 0L : message.deadlineTick,
                    message != null && message.rawValid, ctx.netHandler);
            return null;
        }
    }
}
