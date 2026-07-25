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
    private final long lastIssuedTakeoverRequestId;
    private final boolean takeoverRequestIdsExhausted;
    private final long phaseSequence;
    private final boolean keyDown;
    private final boolean resultPublicationPending;
    private final boolean inventorySyncPending;
    private final boolean ledgerPresent;
    private final int ledgerAnchorSlot;
    private final int ledgerCandidateSlot;

    AutoToolSwapRoundSnapshot(long clientNonce, long serverRoundId, AutoToolSwapRoundState roundState,
            long nextActionSequence, long lastIssuedTakeoverRequestId, boolean takeoverRequestIdsExhausted,
            long phaseSequence, boolean keyDown, boolean resultPublicationPending, boolean inventorySyncPending,
            boolean ledgerPresent, int ledgerAnchorSlot, int ledgerCandidateSlot) {
        if (clientNonce == 0L || serverRoundId < AutoToolSwapProtocol.NO_SERVER_ROUND_ID || roundState == null
                || nextActionSequence < AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE
                || lastIssuedTakeoverRequestId < 0L || phaseSequence < 0L
                || inventorySyncPending && !resultPublicationPending) {
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
        this.lastIssuedTakeoverRequestId = lastIssuedTakeoverRequestId;
        this.takeoverRequestIdsExhausted = takeoverRequestIdsExhausted;
        this.phaseSequence = phaseSequence;
        this.keyDown = keyDown;
        this.resultPublicationPending = resultPublicationPending;
        this.inventorySyncPending = inventorySyncPending;
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

    /** @return 本 round 已烧号的最大 takeoverRequestId；尚未发号时为 0。 */
    public long lastIssuedTakeoverRequestId() {
        return lastIssuedTakeoverRequestId;
    }

    /** @return request ID 是否已在 Long.MAX_VALUE 后永久耗尽。 */
    public boolean takeoverRequestIdsExhausted() {
        return takeoverRequestIdsExhausted;
    }

    public long phaseSequence() {
        return phaseSequence;
    }

    public boolean keyDown() {
        return keyDown;
    }

    /** @return 是否存在等待 ActionResult 正常发送确认的 exact publication。 */
    public boolean hasPendingResultPublication() {
        return resultPublicationPending;
    }

    /** @return pending publication 是否仍须重试完整原版库存同步。 */
    public boolean hasPendingInventorySync() {
        return inventorySyncPending;
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
