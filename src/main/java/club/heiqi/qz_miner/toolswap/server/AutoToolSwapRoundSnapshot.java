package club.heiqi.qz_miner.toolswap.server;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 服务端工具换位 round 的不可变只读快照。 */
public final class AutoToolSwapRoundSnapshot {

    public static final int NO_LEDGER_SLOT = -1;

    private final long clientNonce;
    private final long serverRoundId;
    private final AutoToolSwapRoundState roundState;
    private final long nextActionSequence;
    private final long phaseSequence;
    private final boolean keyDown;
    private final boolean ledgerPresent;
    private final int ledgerAnchorSlot;
    private final int ledgerCandidateSlot;

    AutoToolSwapRoundSnapshot(long clientNonce, long serverRoundId, AutoToolSwapRoundState roundState,
            long nextActionSequence, long phaseSequence, boolean keyDown, boolean ledgerPresent,
            int ledgerAnchorSlot, int ledgerCandidateSlot) {
        if (clientNonce == 0L || serverRoundId < AutoToolSwapProtocol.NO_SERVER_ROUND_ID || roundState == null
                || nextActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE || phaseSequence < 0L) {
            throw new IllegalArgumentException("invalid auto tool swap round snapshot");
        }
        if (ledgerPresent) {
            if (!AutoToolSwapProtocol.isInventorySlot(ledgerAnchorSlot)
                    || !AutoToolSwapProtocol.isInventorySlot(ledgerCandidateSlot)
                    || ledgerAnchorSlot == ledgerCandidateSlot) {
                throw new IllegalArgumentException("invalid ledger slots");
            }
        } else if (ledgerAnchorSlot != NO_LEDGER_SLOT || ledgerCandidateSlot != NO_LEDGER_SLOT) {
            throw new IllegalArgumentException("absent ledger must not expose slots");
        }
        this.clientNonce = clientNonce;
        this.serverRoundId = serverRoundId;
        this.roundState = roundState;
        this.nextActionSequence = nextActionSequence;
        this.phaseSequence = phaseSequence;
        this.keyDown = keyDown;
        this.ledgerPresent = ledgerPresent;
        this.ledgerAnchorSlot = ledgerAnchorSlot;
        this.ledgerCandidateSlot = ledgerCandidateSlot;
    }

    public long clientNonce() {
        return clientNonce;
    }

    public long serverRoundId() {
        return serverRoundId;
    }

    public AutoToolSwapRoundState roundState() {
        return roundState;
    }

    public long nextActionSequence() {
        return nextActionSequence;
    }

    public long phaseSequence() {
        return phaseSequence;
    }

    public boolean keyDown() {
        return keyDown;
    }

    public boolean hasLedger() {
        return ledgerPresent;
    }

    public int ledgerAnchorSlot() {
        return ledgerAnchorSlot;
    }

    public int ledgerCandidateSlot() {
        return ledgerCandidateSlot;
    }
}
