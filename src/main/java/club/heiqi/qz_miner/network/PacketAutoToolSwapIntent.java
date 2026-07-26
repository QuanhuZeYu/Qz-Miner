package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/** 自动工具换位动作意图 C2S 固定帧。 */
public final class PacketAutoToolSwapIntent implements IMessage {

    public static final int FIXED_PAYLOAD_BYTES = 4 + 8 + 8 + 4 + 4 + 4 + 64;

    public int protocolVersion;
    public long serverRoundId;
    /** 普通动作为 actionSequence；TAKEOVER/DECLINE_TAKEOVER 为 takeoverRequestId，布局不变。 */
    public long actionSequence;
    public int actionCode;
    public int anchorSlot;
    public int candidateSlot;
    public long anchorFingerprintFirst;
    public long anchorFingerprintSecond;
    public long anchorFingerprintThird;
    public long anchorFingerprintFourth;
    public long candidateFingerprintFirst;
    public long candidateFingerprintSecond;
    public long candidateFingerprintThird;
    public long candidateFingerprintFourth;
    private boolean rawValid;

    /** FML 反序列化构造。 */
    public PacketAutoToolSwapIntent() {
    }

    /** 从不可变语义意图创建 wire 帧。 */
    public PacketAutoToolSwapIntent(club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        protocolVersion = intent.protocolVersion();
        serverRoundId = intent.serverRoundId();
        actionSequence = intent.actionSequence();
        actionCode = intent.action().wireCode();
        anchorSlot = intent.anchorSlot();
        candidateSlot = intent.candidateSlot();
        copyAnchor(intent.anchorContentFingerprint());
        copyCandidate(intent.candidateContentFingerprint());
        rawValid = true;
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
            anchorSlot = buf.readInt();
            candidateSlot = buf.readInt();
            anchorFingerprintFirst = buf.readLong();
            anchorFingerprintSecond = buf.readLong();
            anchorFingerprintThird = buf.readLong();
            anchorFingerprintFourth = buf.readLong();
            candidateFingerprintFirst = buf.readLong();
            candidateFingerprintSecond = buf.readLong();
            candidateFingerprintThird = buf.readLong();
            candidateFingerprintFourth = buf.readLong();
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
        buf.writeInt(anchorSlot);
        buf.writeInt(candidateSlot);
        buf.writeLong(anchorFingerprintFirst);
        buf.writeLong(anchorFingerprintSecond);
        buf.writeLong(anchorFingerprintThird);
        buf.writeLong(anchorFingerprintFourth);
        buf.writeLong(candidateFingerprintFirst);
        buf.writeLong(candidateFingerprintSecond);
        buf.writeLong(candidateFingerprintThird);
        buf.writeLong(candidateFingerprintFourth);
    }

    /** @return 是否完整捕获了固定长度原始字段。 */
    public boolean isRawValid() {
        return rawValid;
    }

    private void copyAnchor(AutoToolSwapContentFingerprint fingerprint) {
        anchorFingerprintFirst = fingerprint.firstLong();
        anchorFingerprintSecond = fingerprint.secondLong();
        anchorFingerprintThird = fingerprint.thirdLong();
        anchorFingerprintFourth = fingerprint.fourthLong();
    }

    private void copyCandidate(AutoToolSwapContentFingerprint fingerprint) {
        candidateFingerprintFirst = fingerprint.firstLong();
        candidateFingerprintSecond = fingerprint.secondLong();
        candidateFingerprintThird = fingerprint.thirdLong();
        candidateFingerprintFourth = fingerprint.fourthLong();
    }

    /** Netty Handler 不解码 enum 或构造语义意图。 */
    public static final class Handler implements IMessageHandler<PacketAutoToolSwapIntent, IMessage> {
        @Override
        public IMessage onMessage(PacketAutoToolSwapIntent message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerAutoToolSwapRequestDispatch.submitIntent(
                    player,
                    message == null ? 0 : message.protocolVersion,
                    message == null ? 0L : message.serverRoundId,
                    message == null ? 0L : message.actionSequence,
                    message == null ? 0 : message.actionCode,
                    message == null ? 0 : message.anchorSlot,
                    message == null ? 0 : message.candidateSlot,
                    message == null ? 0L : message.anchorFingerprintFirst,
                    message == null ? 0L : message.anchorFingerprintSecond,
                    message == null ? 0L : message.anchorFingerprintThird,
                    message == null ? 0L : message.anchorFingerprintFourth,
                    message == null ? 0L : message.candidateFingerprintFirst,
                    message == null ? 0L : message.candidateFingerprintSecond,
                    message == null ? 0L : message.candidateFingerprintThird,
                    message == null ? 0L : message.candidateFingerprintFourth,
                    message != null && message.rawValid);
            return null;
        }
    }
}
