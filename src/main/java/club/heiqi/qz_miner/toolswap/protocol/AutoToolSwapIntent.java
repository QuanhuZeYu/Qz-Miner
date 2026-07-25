package club.heiqi.qz_miner.toolswap.protocol;

import java.io.Serializable;

/** 客户端到服务端的不可变工具换位请求载荷。 */
public final class AutoToolSwapIntent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int protocolVersion;
    private final long serverRoundId;
    private final long actionSequence;
    private final AutoToolSwapAction action;
    private final int anchorSlot;
    private final int candidateSlot;
    private final AutoToolSwapContentFingerprint anchorContentFingerprint;
    private final AutoToolSwapContentFingerprint candidateContentFingerprint;

    public AutoToolSwapIntent(int protocolVersion, long serverRoundId, long actionSequence,
            AutoToolSwapAction action, int anchorSlot, int candidateSlot,
            AutoToolSwapContentFingerprint anchorContentFingerprint,
            AutoToolSwapContentFingerprint candidateContentFingerprint) {
        if (protocolVersion < 0 || serverRoundId < AutoToolSwapProtocol.NO_SERVER_ROUND_ID
                || actionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE || action == null
                || !AutoToolSwapProtocol.isInventorySlot(anchorSlot)
                || !AutoToolSwapProtocol.isInventorySlot(candidateSlot)
                || anchorContentFingerprint == null || candidateContentFingerprint == null) {
            throw new IllegalArgumentException("invalid auto tool swap intent");
        }
        this.protocolVersion = protocolVersion;
        this.serverRoundId = serverRoundId;
        this.actionSequence = actionSequence;
        this.action = action;
        this.anchorSlot = anchorSlot;
        this.candidateSlot = candidateSlot;
        this.anchorContentFingerprint = anchorContentFingerprint;
        this.candidateContentFingerprint = candidateContentFingerprint;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public long serverRoundId() {
        return serverRoundId;
    }

    /**
     * 返回 wire 中保留的 long 关联字段。
     * 普通动作把它解释为 actionSequence，TAKEOVER/DECLINE_TAKEOVER 把它解释为 takeoverRequestId。
     */
    public long actionSequence() {
        return actionSequence;
    }

    /** @return 普通动作使用的 actionSequence；接替动作调用会 fail-closed。 */
    public long ordinaryActionSequence() {
        if (usesTakeoverRequestId()) {
            throw new IllegalStateException("takeover intent does not carry an ordinary action sequence");
        }
        return actionSequence;
    }

    /** @return TAKEOVER/DECLINE_TAKEOVER 使用的独立 takeoverRequestId；普通动作调用会 fail-closed。 */
    public long takeoverRequestId() {
        if (!usesTakeoverRequestId()) {
            throw new IllegalStateException("ordinary intent does not carry a takeover request id");
        }
        return actionSequence;
    }

    /** @return 当前动作是否把保留 long wire 字段解释为 takeoverRequestId。 */
    public boolean usesTakeoverRequestId() {
        return action == AutoToolSwapAction.TAKEOVER || action == AutoToolSwapAction.DECLINE_TAKEOVER;
    }

    public AutoToolSwapAction action() {
        return action;
    }

    public int anchorSlot() {
        return anchorSlot;
    }

    public int candidateSlot() {
        return candidateSlot;
    }

    public AutoToolSwapContentFingerprint anchorContentFingerprint() {
        return anchorContentFingerprint;
    }

    public AutoToolSwapContentFingerprint candidateContentFingerprint() {
        return candidateContentFingerprint;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AutoToolSwapIntent)) {
            return false;
        }
        AutoToolSwapIntent value = (AutoToolSwapIntent) other;
        return protocolVersion == value.protocolVersion && serverRoundId == value.serverRoundId
                && actionSequence == value.actionSequence && action == value.action && anchorSlot == value.anchorSlot
                && candidateSlot == value.candidateSlot
                && anchorContentFingerprint.equals(value.anchorContentFingerprint)
                && candidateContentFingerprint.equals(value.candidateContentFingerprint);
    }

    @Override
    public int hashCode() {
        int value = protocolVersion;
        value = 31 * value + longHash(serverRoundId);
        value = 31 * value + longHash(actionSequence);
        value = 31 * value + action.hashCode();
        value = 31 * value + anchorSlot;
        value = 31 * value + candidateSlot;
        value = 31 * value + anchorContentFingerprint.hashCode();
        return 31 * value + candidateContentFingerprint.hashCode();
    }

    private static int longHash(long value) {
        return (int) (value ^ (value >>> 32));
    }
}
