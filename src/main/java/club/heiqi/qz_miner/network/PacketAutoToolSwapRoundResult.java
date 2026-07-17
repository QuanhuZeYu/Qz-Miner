package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 自动工具换位 round 结果 S2C 固定帧。 */
public final class PacketAutoToolSwapRoundResult implements IMessage {

    public static final int FIXED_PAYLOAD_BYTES = 4 + 8 + 8 + 4 + 4 + 8 + 8;

    public int protocolVersion;
    public long clientNonce;
    public long serverRoundId;
    public int resultCode;
    public int roundState;
    public long nextActionSequence;
    public long serverTick;
    private boolean rawValid;

    /** FML 反序列化构造。 */
    public PacketAutoToolSwapRoundResult() {
    }

    /** 从不可变服务端结果创建 wire 帧。 */
    public PacketAutoToolSwapRoundResult(int protocolVersion, long clientNonce, AutoToolSwapRoundResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        this.protocolVersion = protocolVersion;
        this.clientNonce = clientNonce;
        this.serverRoundId = result.serverRoundId();
        this.resultCode = result.outcome().wireCode();
        this.roundState = result.roundState().wireCode();
        this.nextActionSequence = result.nextActionSequence();
        this.serverTick = result.serverTick();
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
            clientNonce = buf.readLong();
            serverRoundId = buf.readLong();
            resultCode = buf.readInt();
            roundState = buf.readInt();
            nextActionSequence = buf.readLong();
            serverTick = buf.readLong();
            rawValid = true;
        } catch (IndexOutOfBoundsException error) {
            rawValid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(protocolVersion);
        buf.writeLong(clientNonce);
        buf.writeLong(serverRoundId);
        buf.writeInt(resultCode);
        buf.writeInt(roundState);
        buf.writeLong(nextActionSequence);
        buf.writeLong(serverTick);
    }

    /** @return 是否完整捕获了固定长度原始字段。 */
    public boolean isRawValid() {
        return rawValid;
    }

    /** S2C Netty Handler 只向 common proxy 转交原始字段。 */
    public static final class Handler implements IMessageHandler<PacketAutoToolSwapRoundResult, IMessage> {
        @Override
        public IMessage onMessage(PacketAutoToolSwapRoundResult message, MessageContext ctx) {
            MyMod.proxy.handleClientAutoToolSwapRoundResult(
                    message == null ? 0 : message.protocolVersion,
                    message == null ? 0L : message.clientNonce,
                    message == null ? 0L : message.serverRoundId,
                    message == null ? 0 : message.resultCode,
                    message == null ? 0 : message.roundState,
                    message == null ? 0L : message.nextActionSequence,
                    message == null ? 0L : message.serverTick,
                    message != null && message.rawValid,
                    ctx.netHandler);
            return null;
        }
    }
}
