package club.heiqi.qz_miner.toolswap.protocol;

import java.io.Serializable;

/** 服务端 round 的无歧义响应快照。 */
public final class AutoToolSwapRoundResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long serverRoundId;
    private final AutoToolSwapResultCode outcome;
    private final AutoToolSwapRoundState roundState;
    private final long nextActionSequence;
    private final long serverTick;

    public AutoToolSwapRoundResult(long serverRoundId, AutoToolSwapResultCode outcome,
            AutoToolSwapRoundState roundState, long nextActionSequence, long serverTick) {
        if (serverRoundId < AutoToolSwapProtocol.NO_SERVER_ROUND_ID || outcome == null || roundState == null
                || nextActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE || serverTick < 0L) {
            throw new IllegalArgumentException("invalid auto tool swap round result");
        }
        this.serverRoundId = serverRoundId;
        this.outcome = outcome;
        this.roundState = roundState;
        this.nextActionSequence = nextActionSequence;
        this.serverTick = serverTick;
    }

    public long serverRoundId() {
        return serverRoundId;
    }

    public AutoToolSwapResultCode outcome() {
        return outcome;
    }

    public AutoToolSwapRoundState roundState() {
        return roundState;
    }

    public long nextActionSequence() {
        return nextActionSequence;
    }

    public long serverTick() {
        return serverTick;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AutoToolSwapRoundResult)) {
            return false;
        }
        AutoToolSwapRoundResult value = (AutoToolSwapRoundResult) other;
        return serverRoundId == value.serverRoundId && outcome == value.outcome && roundState == value.roundState
                && nextActionSequence == value.nextActionSequence && serverTick == value.serverTick;
    }

    @Override
    public int hashCode() {
        int value = longHash(serverRoundId);
        value = 31 * value + outcome.hashCode();
        value = 31 * value + roundState.hashCode();
        value = 31 * value + longHash(nextActionSequence);
        return 31 * value + longHash(serverTick);
    }

    private static int longHash(long value) {
        return (int) (value ^ (value >>> 32));
    }
}
