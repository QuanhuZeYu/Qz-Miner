package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 自动工具换位 round 阶段 S2C 固定帧。 */
public final class PacketAutoToolSwapRoundPhase implements IMessage {

    public static final int FIXED_PAYLOAD_BYTES = 4 + 8 + 8 + 4 + 4 + 8;

    public int protocolVersion;
    public long serverRoundId;
    public long phaseSequence;
    public int phaseOrdinal;
    public int generation;
    public long serverTick;
    private boolean rawValid;

    /** FML 反序列化构造。 */
    public PacketAutoToolSwapRoundPhase() {
    }

    /** 创建完整阶段快照。 */
    public PacketAutoToolSwapRoundPhase(int protocolVersion, long serverRoundId, long phaseSequence,
            int phaseOrdinal, int generation, long serverTick) {
        this.protocolVersion = protocolVersion;
        this.serverRoundId = serverRoundId;
        this.phaseSequence = phaseSequence;
        this.phaseOrdinal = phaseOrdinal;
        this.generation = generation;
        this.serverTick = serverTick;
        this.rawValid = true;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        rawValid = false;
        if (buf == null || buf.readableBytes() != FIXED_PAYLOAD_BYTES) {
            return;
        }
        try {
            protocolVersion = buf.readInt();
            serverRoundId = buf.readLong();
            phaseSequence = buf.readLong();
            phaseOrdinal = buf.readInt();
            generation = buf.readInt();
            serverTick = buf.readLong();
            rawValid = true;
        } catch (IndexOutOfBoundsException error) {
            rawValid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(protocolVersion);
        buf.writeLong(serverRoundId);
        buf.writeLong(phaseSequence);
        buf.writeInt(phaseOrdinal);
        buf.writeInt(generation);
        buf.writeLong(serverTick);
    }

    /** @return 是否完整捕获了固定长度原始字段。 */
    public boolean isRawValid() {
        return rawValid;
    }

    /** S2C Netty Handler 只向 common proxy 转交原始字段。 */
    public static final class Handler implements IMessageHandler<PacketAutoToolSwapRoundPhase, IMessage> {
        @Override
        public IMessage onMessage(PacketAutoToolSwapRoundPhase message, MessageContext ctx) {
            MyMod.proxy.handleClientAutoToolSwapRoundPhase(
                    message == null ? 0 : message.protocolVersion,
                    message == null ? 0L : message.serverRoundId,
                    message == null ? 0L : message.phaseSequence,
                    message == null ? 0 : message.phaseOrdinal,
                    message == null ? 0 : message.generation,
                    message == null ? 0L : message.serverTick,
                    message != null && message.rawValid,
                    ctx.netHandler);
            return null;
        }
    }
}
