package club.heiqi.qz_miner.toolswap.server;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 服务端自动工具 round projection 的不可变快照。 */
public final class AutoToolSwapRoundSnapshot {

    private final long clientNonce;
    private final long serverRoundId;
    private final AutoToolSwapRoundState roundState;
    private final long nextActionSequence;
    private final long phaseSequence;
    private final boolean keyDown;
    private final boolean resultPublicationPending;

    AutoToolSwapRoundSnapshot(long clientNonce, long serverRoundId, AutoToolSwapRoundState roundState,
            long nextActionSequence, long phaseSequence, boolean keyDown, boolean resultPublicationPending) {
        if (clientNonce == 0L || serverRoundId < AutoToolSwapProtocol.NO_SERVER_ROUND_ID || roundState == null
                || nextActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE || phaseSequence < 0L) {
            throw new IllegalArgumentException("invalid auto tool swap round snapshot");
        }
        this.clientNonce = clientNonce;
        this.serverRoundId = serverRoundId;
        this.roundState = roundState;
        this.nextActionSequence = nextActionSequence;
        this.phaseSequence = phaseSequence;
        this.keyDown = keyDown;
        this.resultPublicationPending = resultPublicationPending;
    }

    public long clientNonce() { return clientNonce; }
    public long serverRoundId() { return serverRoundId; }
    public AutoToolSwapRoundState roundState() { return roundState; }
    public long nextActionSequence() { return nextActionSequence; }
    public long phaseSequence() { return phaseSequence; }
    public boolean keyDown() { return keyDown; }
    public boolean hasPendingResultPublication() { return resultPublicationPending; }
}
