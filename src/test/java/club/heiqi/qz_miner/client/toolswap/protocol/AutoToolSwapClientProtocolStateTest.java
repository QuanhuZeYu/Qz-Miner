package club.heiqi.qz_miner.client.toolswap.protocol;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 客户端自动工具换位协议状态核心的纯 JVM 合同。 */
public class AutoToolSwapClientProtocolStateTest {

    private static final AutoToolSwapContentFingerprint ANCHOR = fingerprint("anchor");
    private static final AutoToolSwapContentFingerprint CANDIDATE = fingerprint("candidate");

    @Test
    public void publicNonceIsProcessWideAndInjectedExhaustionNeverWraps() {
        AutoToolSwapClientProtocolState first = new AutoToolSwapClientProtocolState();
        AutoToolSwapClientProtocolState second = new AutoToolSwapClientProtocolState();
        long firstNonce = first.beginRound();
        long secondNonce = second.beginRound();

        Assert.assertTrue(firstNonce > 0L);
        Assert.assertTrue(secondNonce > 0L);
        Assert.assertNotEquals(firstNonce, secondNonce);

        AutoToolSwapClientProtocolState exhausted = new AutoToolSwapClientProtocolState(sequence(Long.MAX_VALUE, 0L));
        Assert.assertEquals(Long.MAX_VALUE, exhausted.beginRound());
        exhausted.reset();
        Assert.assertEquals(0L, exhausted.beginRound());
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.IDLE, exhausted.snapshot().phase());
    }

    @Test
    public void roundAcknowledgementRejectsOldNonceAndResetInvalidatesPriorRound() {
        AutoToolSwapClientProtocolState state = new AutoToolSwapClientProtocolState(sequence(11L, 12L));
        Assert.assertEquals(11L, state.beginRound());
        Assert.assertNull(roundResult(state, 10L, 1L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, 1L, 1L));
        Assert.assertNotNull(roundResult(state, 11L, 7L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, 3L, 2L));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.OPEN, state.snapshot().phase());
        Assert.assertEquals(7L, state.snapshot().serverRoundId());
        Assert.assertNotNull(roundResult(state, 11L, 7L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, 3L, 2L));
        Assert.assertEquals(3L, state.snapshot().nextActionSequence());

        state.reset();
        Assert.assertNull(roundResult(state, 11L, 7L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, 3L, 2L));
        Assert.assertEquals(12L, state.beginRound());
        Assert.assertNotNull(roundResult(state, 12L, 8L, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.OPEN, 1L, 3L));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.IDLE, state.snapshot().phase());
    }

    @Test
    public void actionKeepsOneImmutableIntentForRetryAndOnlyAllowsClosingActions() {
        AutoToolSwapClientProtocolState state = openState(21L, 4L);
        AutoToolSwapIntent swap = state.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE);
        Assert.assertNotNull(swap);
        Assert.assertSame(swap, state.inFlightIntent());
        Assert.assertNull(state.beginAction(AutoToolSwapAction.FREEZE, 0, 9, ANCHOR, CANDIDATE));
        Assert.assertEquals(4L, state.snapshot().nextActionSequence());

        state.abandonInFlight();
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, state.snapshot().phase());
        Assert.assertNull(state.beginAction(AutoToolSwapAction.RESTORE, 0, 9, ANCHOR, CANDIDATE));

        state = openState(22L, 1L);
        state.markClosing();
        Assert.assertNull(state.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE));
        Assert.assertNotNull(state.beginAction(AutoToolSwapAction.RESTORE, 0, 9, ANCHOR, CANDIDATE));
    }

    @Test
    public void actionResultRequiresExactTupleRejectAdvancesAndDuplicateIsIdempotent() {
        AutoToolSwapClientProtocolState state = openState(31L, 5L);
        AutoToolSwapIntent intent = state.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE);
        Assert.assertNull(actionResult(state, intent, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.OPEN, 6L, 1L, AutoToolSwapAction.FREEZE, 0, 9));
        Assert.assertNull(actionResult(state, intent, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.OPEN, 6L, 1L, AutoToolSwapAction.SWAP, 1, 9));
        Assert.assertNull(actionResult(state, intent, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.OPEN, 6L, 1L, AutoToolSwapAction.SWAP, 0, 8));
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, intent.serverRoundId(),
                intent.actionSequence() + 1L, intent.action().wireCode(), AutoToolSwapResultCode.REJECTED.wireCode(),
                AutoToolSwapRoundState.OPEN.wireCode(), 0, 9, 6L, 1L, true));

        AutoToolSwapClientProtocolSettlement settled = actionResult(state, intent, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.OPEN, 6L, 1L, AutoToolSwapAction.SWAP, 0, 9);
        Assert.assertNotNull(settled);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, settled.result().outcome());
        Assert.assertEquals(6L, state.snapshot().nextActionSequence());
        Assert.assertSame(settled, actionResult(state, intent, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.OPEN, 6L, 1L, AutoToolSwapAction.SWAP, 0, 9));
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, intent.serverRoundId(),
                intent.actionSequence(), intent.action().wireCode(), AutoToolSwapResultCode.REJECTED.wireCode(),
                AutoToolSwapRoundState.OPEN.wireCode(), 0, 9, 6L, 2L, true));
    }

    @Test
    public void syncFailureFinishedAndSequenceOverflowFailClosed() {
        AutoToolSwapClientProtocolState syncFailed = openState(41L, 1L);
        AutoToolSwapIntent first = syncFailed.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE);
        Assert.assertNotNull(actionResult(syncFailed, first, AutoToolSwapResultCode.SYNC_FAILED,
                AutoToolSwapRoundState.ORPHANED, 2L, 1L, AutoToolSwapAction.SWAP, 0, 9));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, syncFailed.snapshot().phase());

        AutoToolSwapClientProtocolState finished = openState(42L, 2L);
        AutoToolSwapIntent close = finished.beginAction(AutoToolSwapAction.CLOSE, 0, 9, ANCHOR, CANDIDATE);
        Assert.assertNotNull(actionResult(finished, close, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED, 3L, 2L, AutoToolSwapAction.CLOSE, 0, 9));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.FINISHED, finished.snapshot().phase());

        AutoToolSwapClientProtocolState overflow = openState(43L, Long.MAX_VALUE);
        Assert.assertNull(overflow.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, overflow.snapshot().phase());
    }

    @Test
    public void dedicatedPhaseRequiresCurrentRoundAndStrictlyIncreasingSequence() {
        AutoToolSwapClientProtocolState state = openState(51L, 1L);
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 1L,
                ChainPhase.ARMED.ordinal(), 0, 1L, true));
        AutoToolSwapClientProtocolPhaseSnapshot first = state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION,
                51L, 1L, ChainPhase.PLANNING.ordinal(), 2, 3L, true);
        Assert.assertNotNull(first);
        Assert.assertEquals(ChainPhase.PLANNING, first.phase());
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 51L, 1L,
                ChainPhase.RUNNING.ordinal(), 3, 4L, true));
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 51L, 0L,
                ChainPhase.RUNNING.ordinal(), 3, 4L, true));
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 51L, 2L,
                ChainPhase.RUNNING.ordinal(), -1, 4L, true));
        Assert.assertNotNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 51L, 2L,
                ChainPhase.RUNNING.ordinal(), 3, 4L, true));
        Assert.assertEquals(2L, state.snapshot().lastPhaseSequence());
    }

    private static AutoToolSwapClientProtocolState openState(long roundId, long nextActionSequence) {
        AutoToolSwapClientProtocolState state = new AutoToolSwapClientProtocolState(sequence(roundId + 100L));
        long nonce = state.beginRound();
        Assert.assertNotNull(roundResult(state, nonce, roundId, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, nextActionSequence, 0L));
        return state;
    }

    private static AutoToolSwapClientProtocolSnapshot roundResult(AutoToolSwapClientProtocolState state,
            long nonce, long roundId, AutoToolSwapResultCode outcome, AutoToolSwapRoundState roundState,
            long nextActionSequence, long serverTick) {
        return state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, roundId, outcome.wireCode(),
                roundState.wireCode(), nextActionSequence, serverTick, true);
    }

    private static AutoToolSwapClientProtocolSettlement actionResult(AutoToolSwapClientProtocolState state,
            AutoToolSwapIntent intent, AutoToolSwapResultCode outcome, AutoToolSwapRoundState roundState,
            long nextActionSequence, long serverTick, AutoToolSwapAction responseAction,
            int anchorSlot, int candidateSlot) {
        return state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, intent.serverRoundId(),
                intent.actionSequence(), responseAction.wireCode(), outcome.wireCode(), roundState.wireCode(),
                anchorSlot, candidateSlot, nextActionSequence, serverTick, true);
    }

    private static AutoToolSwapContentFingerprint fingerprint(String content) {
        return AutoToolSwapContentFingerprint.fromContent("qz_miner:test", content);
    }

    private static AutoToolSwapClientNonceAllocator sequence(final long... values) {
        return new AutoToolSwapClientNonceAllocator() {
            private int index;

            @Override
            public long allocate() {
                return index < values.length ? values[index++] : 0L;
            }
        };
    }
}
