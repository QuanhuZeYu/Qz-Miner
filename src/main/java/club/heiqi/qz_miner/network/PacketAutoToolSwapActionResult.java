package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 自动工具换位动作结果 S2C 固定帧。 */
public final class PacketAutoToolSwapActionResult implements IMessage {

    public static final int FIXED_PAYLOAD_BYTES = 4 + 8 + 8 + 4 + 4 + 4 + 4 + 4 + 8 + 8;

    public int protocolVersion;
    public long serverRoundId;
    /** 回显 intent 的关联 long：普通动作为 actionSequence，接替动作为 takeoverRequestId。 */
    public long actionSequence;
    public int actionCode;
    public int resultCode;
    public int roundState;
    public int anchorSlot;
    public int candidateSlot;
    /** 始终为普通动作命名空间的当前水位。 */
    public long nextActionSequence;
    public long serverTick;
    private boolean rawValid;

    /** FML 反序列化构造。 */
    public PacketAutoToolSwapActionResult() {
    }

    /** 从不可变意图和结算结果创建 wire 帧。 */
    public PacketAutoToolSwapActionResult(AutoToolSwapIntent intent, AutoToolSwapRoundResult result) {
        if (intent == null || result == null) {
            throw new IllegalArgumentException("intent and result must not be null");
        }
        protocolVersion = intent.protocolVersion();
        serverRoundId = result.serverRoundId();
        actionSequence = intent.actionSequence();
        actionCode = intent.action().wireCode();
        resultCode = result.outcome().wireCode();
        roundState = result.roundState().wireCode();
        anchorSlot = intent.anchorSlot();
        candidateSlot = intent.candidateSlot();
        nextActionSequence = result.nextActionSequence();
        serverTick = result.serverTick();
        rawValid = true;
    }

    /** 从原始请求字段和结算结果创建拒绝回执。 */
    public PacketAutoToolSwapActionResult(int protocolVersion, long serverRoundId, long actionSequence,
            int actionCode, int anchorSlot, int candidateSlot, AutoToolSwapRoundResult result) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        this.protocolVersion = protocolVersion;
        this.serverRoundId = serverRoundId;
        this.actionSequence = actionSequence;
        this.actionCode = actionCode;
        this.resultCode = result.outcome().wireCode();
        this.roundState = result.roundState().wireCode();
        this.anchorSlot = anchorSlot;
        this.candidateSlot = candidateSlot;
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
            serverRoundId = buf.readLong();
            actionSequence = buf.readLong();
            actionCode = buf.readInt();
            resultCode = buf.readInt();
            roundState = buf.readInt();
            anchorSlot = buf.readInt();
            candidateSlot = buf.readInt();
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
        buf.writeLong(serverRoundId);
        buf.writeLong(actionSequence);
        buf.writeInt(actionCode);
        buf.writeInt(resultCode);
        buf.writeInt(roundState);
        buf.writeInt(anchorSlot);
        buf.writeInt(candidateSlot);
        buf.writeLong(nextActionSequence);
        buf.writeLong(serverTick);
    }

    /** @return 是否完整捕获了固定长度原始字段。 */
    public boolean isRawValid() {
        return rawValid;
    }

    /** S2C Netty Handler 只向 common proxy 转交原始字段。 */
    public static final class Handler implements IMessageHandler<PacketAutoToolSwapActionResult, IMessage> {
        @Override
        public IMessage onMessage(PacketAutoToolSwapActionResult message, MessageContext ctx) {
            MyMod.proxy.handleClientAutoToolSwapActionResult(
                    message == null ? 0 : message.protocolVersion,
                    message == null ? 0L : message.serverRoundId,
                    message == null ? 0L : message.actionSequence,
                    message == null ? 0 : message.actionCode,
                    message == null ? 0 : message.resultCode,
                    message == null ? 0 : message.roundState,
                    message == null ? 0 : message.anchorSlot,
                    message == null ? 0 : message.candidateSlot,
                    message == null ? 0L : message.nextActionSequence,
                    message == null ? 0L : message.serverTick,
                    message != null && message.rawValid,
                    ctx.netHandler);
            return null;
        }
    }
}
