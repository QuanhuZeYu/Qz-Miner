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
    public void waitingRoundClosingNeverReopensOnLateAcceptedResult() {
        AutoToolSwapRoundState[] serverStates = {
                AutoToolSwapRoundState.OPEN,
                AutoToolSwapRoundState.CLOSING
        };
        for (int index = 0; index < serverStates.length; index++) {
            AutoToolSwapClientProtocolState state = new AutoToolSwapClientProtocolState(sequence(70L + index));
            long nonce = state.beginRound();
            state.markClosing();
            Assert.assertEquals(AutoToolSwapClientProtocolPhase.WAIT_ROUND_CLOSING, state.snapshot().phase());

            Assert.assertNotNull(roundResult(state, nonce, 80L + index, AutoToolSwapResultCode.ACCEPTED,
                    serverStates[index], 1L, 1L));
            Assert.assertEquals(AutoToolSwapClientProtocolPhase.CLOSING, state.snapshot().phase());
            Assert.assertNull(state.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE));
            Assert.assertNotNull(state.beginControlAction(AutoToolSwapAction.CLOSE));
        }
    }

    @Test
    public void acceptedNonOpenRoundStatesOnlyAllowClosingActions() {
        AutoToolSwapRoundState[] serverStates = {
                AutoToolSwapRoundState.SWAPPED,
                AutoToolSwapRoundState.FROZEN,
                AutoToolSwapRoundState.CLOSING
        };
        for (int index = 0; index < serverStates.length; index++) {
            AutoToolSwapClientProtocolState state = new AutoToolSwapClientProtocolState(sequence(90L + index));
            long nonce = state.beginRound();
            Assert.assertNotNull(roundResult(state, nonce, 100L + index, AutoToolSwapResultCode.ACCEPTED,
                    serverStates[index], 1L, 1L));
            Assert.assertEquals(AutoToolSwapClientProtocolPhase.CLOSING, state.snapshot().phase());
            Assert.assertNull(state.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE));
            Assert.assertNotNull(state.beginControlAction(AutoToolSwapAction.CLOSE));
        }
    }

    @Test
    public void zeroRoundIdRejectedResultDeterministicallyLeavesWaiting() {
        AutoToolSwapClientProtocolState state = new AutoToolSwapClientProtocolState(sequence(110L));
        long nonce = state.beginRound();

        Assert.assertNotNull(roundResult(state, nonce, AutoToolSwapProtocol.NO_SERVER_ROUND_ID,
                AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.PENDING_KEY, 1L, 2L));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.IDLE, state.snapshot().phase());
        Assert.assertEquals(0L, state.snapshot().pendingNonce());
        Assert.assertEquals(AutoToolSwapProtocol.NO_SERVER_ROUND_ID, state.snapshot().serverRoundId());
        Assert.assertNull(state.snapshot().serverRoundState());
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
    public void controlActionsUseOneCanonicalPayloadAndOneInFlightSequence() {
        AutoToolSwapClientProtocolState state = openState(23L, 7L);
        Assert.assertNull(state.beginControlAction(null));
        Assert.assertNull(state.beginControlAction(AutoToolSwapAction.SWAP));
        Assert.assertNull(state.beginControlAction(AutoToolSwapAction.RESTORE));
        Assert.assertNull(state.beginAction(AutoToolSwapAction.FREEZE, 1, 2, ANCHOR, CANDIDATE));
        Assert.assertNull(state.beginAction(AutoToolSwapAction.CLOSE, 1, 2, ANCHOR, CANDIDATE));

        AutoToolSwapIntent freeze = state.beginControlAction(AutoToolSwapAction.FREEZE);
        Assert.assertNotNull(freeze);
        Assert.assertEquals(0, freeze.anchorSlot());
        Assert.assertEquals(0, freeze.candidateSlot());
        Assert.assertSame(AutoToolSwapContentFingerprint.CANONICAL_EMPTY,
                freeze.anchorContentFingerprint());
        Assert.assertSame(AutoToolSwapContentFingerprint.CANONICAL_EMPTY,
                freeze.candidateContentFingerprint());
        Assert.assertSame(freeze, state.inFlightIntent());
        Assert.assertEquals(7L, state.snapshot().nextActionSequence());
        Assert.assertNull(state.beginControlAction(AutoToolSwapAction.CLOSE));

        AutoToolSwapClientProtocolState closing = openState(26L, 2L);
        closing.markClosing();
        AutoToolSwapIntent close = closing.beginControlAction(AutoToolSwapAction.CLOSE);
        Assert.assertNotNull(close);
        Assert.assertEquals(0, close.anchorSlot());
        Assert.assertEquals(0, close.candidateSlot());
        Assert.assertSame(AutoToolSwapContentFingerprint.CANONICAL_EMPTY,
                close.anchorContentFingerprint());
        Assert.assertSame(AutoToolSwapContentFingerprint.CANONICAL_EMPTY,
                close.candidateContentFingerprint());
    }

    @Test
    public void frozenActionResultForbidsSwapButAllowsRestoreAndClose() {
        AutoToolSwapClientProtocolState restoreState = frozenState(24L);
        Assert.assertNull(restoreState.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE));
        Assert.assertNotNull(restoreState.beginAction(AutoToolSwapAction.RESTORE, 0, 9, ANCHOR, CANDIDATE));

        AutoToolSwapClientProtocolState closeState = frozenState(25L);
        Assert.assertNull(closeState.beginControlAction(AutoToolSwapAction.FREEZE));
        Assert.assertNotNull(closeState.beginControlAction(AutoToolSwapAction.CLOSE));
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
        AutoToolSwapIntent close = finished.beginControlAction(AutoToolSwapAction.CLOSE);
        Assert.assertNotNull(actionResult(finished, close, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED, 3L, 2L, AutoToolSwapAction.CLOSE, 0, 0));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.FINISHED, finished.snapshot().phase());

        AutoToolSwapClientProtocolState orphaned = openState(44L, 3L);
        AutoToolSwapIntent orphanedSwap = orphaned.beginAction(AutoToolSwapAction.SWAP, 0, 9,
                ANCHOR, CANDIDATE);
        Assert.assertNotNull(actionResult(orphaned, orphanedSwap, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.ORPHANED, 4L, 3L, AutoToolSwapAction.SWAP, 0, 9));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, orphaned.snapshot().phase());

        AutoToolSwapClientProtocolState overflow = openState(43L, Long.MAX_VALUE);
        Assert.assertNull(overflow.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, overflow.snapshot().phase());
    }

    @Test
    public void actionResultStateMappingNeverExpandsClosingPermissions() {
        AutoToolSwapClientProtocolState swapped = openState(45L, 1L);
        AutoToolSwapIntent swap = swapped.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE);
        Assert.assertNotNull(actionResult(swapped, swap, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 2L, 1L, AutoToolSwapAction.SWAP, 0, 9));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.OPEN, swapped.snapshot().phase());

        AutoToolSwapClientProtocolState rejectedClosing = openState(46L, 4L);
        AutoToolSwapIntent rejectedSwap = rejectedClosing.beginAction(AutoToolSwapAction.SWAP, 0, 9,
                ANCHOR, CANDIDATE);
        Assert.assertNotNull(actionResult(rejectedClosing, rejectedSwap, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.CLOSING, 5L, 2L, AutoToolSwapAction.SWAP, 0, 9));
        Assert.assertEquals(5L, rejectedClosing.snapshot().nextActionSequence());
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.CLOSING, rejectedClosing.snapshot().phase());

        AutoToolSwapClientProtocolState locallyClosing = openState(47L, 2L);
        locallyClosing.markClosing();
        AutoToolSwapIntent restore = locallyClosing.beginAction(AutoToolSwapAction.RESTORE, 0, 9,
                ANCHOR, CANDIDATE);
        Assert.assertNotNull(actionResult(locallyClosing, restore, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.OPEN, 3L, 3L, AutoToolSwapAction.RESTORE, 0, 9));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.CLOSING, locallyClosing.snapshot().phase());
    }

    @Test
    public void abandonCurrentRoundCoversWaitingAndActivePhasesWithoutRecoveryIntent() {
        AutoToolSwapClientProtocolState waiting = new AutoToolSwapClientProtocolState(sequence(120L));
        waiting.beginRound();
        waiting.abandonCurrentRound();
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, waiting.snapshot().phase());
        Assert.assertNull(waiting.inFlightIntent());

        AutoToolSwapClientProtocolState waitingClosing = new AutoToolSwapClientProtocolState(sequence(121L));
        waitingClosing.beginRound();
        waitingClosing.markClosing();
        waitingClosing.abandonCurrentRound();
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, waitingClosing.snapshot().phase());

        AutoToolSwapClientProtocolState active = openState(48L, 1L);
        active.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE);
        active.abandonCurrentRound();
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, active.snapshot().phase());
        Assert.assertNull(active.inFlightIntent());

        AutoToolSwapClientProtocolState closing = openState(49L, 1L);
        closing.markClosing();
        closing.abandonCurrentRound();
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.ORPHANED, closing.snapshot().phase());
    }

    @Test
    public void malformedRoundResultsHaveNoEffect() {
        AutoToolSwapClientProtocolState state = new AutoToolSwapClientProtocolState(sequence(130L));
        long nonce = state.beginRound();

        Assert.assertNull(state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, 0L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true));
        assertWaiting(state, nonce);
        Assert.assertNull(state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, 0L,
                AutoToolSwapResultCode.SYNC_FAILED.wireCode(), AutoToolSwapRoundState.ORPHANED.wireCode(),
                1L, 0L, true));
        assertWaiting(state, nonce);
        Assert.assertNull(roundResult(state, nonce, 1L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.PENDING_KEY, 1L, 0L));
        assertWaiting(state, nonce);
        Assert.assertNull(roundResult(state, nonce, 1L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED, 1L, 0L));
        assertWaiting(state, nonce);
        Assert.assertNull(roundResult(state, nonce, 1L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.ORPHANED, 1L, 0L));
        assertWaiting(state, nonce);
        Assert.assertNull(state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, 1L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, false));
        assertWaiting(state, nonce);
        Assert.assertNull(state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION + 1, nonce, 1L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true));
        assertWaiting(state, nonce);
        Assert.assertNull(state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, 1L,
                Integer.MAX_VALUE, AutoToolSwapRoundState.OPEN.wireCode(), 1L, 0L, true));
        assertWaiting(state, nonce);
        Assert.assertNull(state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, 1L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), Integer.MAX_VALUE, 1L, 0L, true));
        assertWaiting(state, nonce);
        Assert.assertNull(state.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, 1L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                0L, 0L, true));
        assertWaiting(state, nonce);

        Assert.assertNotNull(roundResult(state, nonce, 1L, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, 1L, 0L));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.OPEN, state.snapshot().phase());
    }

    @Test
    public void malformedActionResultsHaveNoEffect() {
        AutoToolSwapClientProtocolState state = openState(52L, 3L);
        AutoToolSwapIntent intent = state.beginAction(AutoToolSwapAction.SWAP, 0, 9, ANCHOR, CANDIDATE);

        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 3L,
                AutoToolSwapAction.SWAP.wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.SWAPPED.wireCode(), 0, 9, 4L, 0L, false));
        assertPendingIntent(state, intent, 3L);
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION + 1, 52L, 3L,
                AutoToolSwapAction.SWAP.wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.SWAPPED.wireCode(), 0, 9, 4L, 0L, true));
        assertPendingIntent(state, intent, 3L);
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 3L,
                Integer.MAX_VALUE, AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.SWAPPED.wireCode(), 0, 9, 4L, 0L, true));
        assertPendingIntent(state, intent, 3L);
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 3L,
                AutoToolSwapAction.SWAP.wireCode(), Integer.MAX_VALUE,
                AutoToolSwapRoundState.SWAPPED.wireCode(), 0, 9, 4L, 0L, true));
        assertPendingIntent(state, intent, 3L);
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 3L,
                AutoToolSwapAction.SWAP.wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                Integer.MAX_VALUE, 0, 9, 4L, 0L, true));
        assertPendingIntent(state, intent, 3L);
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 3L,
                AutoToolSwapAction.SWAP.wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.PENDING_KEY.wireCode(), 0, 9, 4L, 0L, true));
        assertPendingIntent(state, intent, 3L);
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 3L,
                AutoToolSwapAction.SWAP.wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.SWAPPED.wireCode(), 0, 9, 0L, 0L, true));
        assertPendingIntent(state, intent, 3L);
        Assert.assertNull(state.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, 52L, 3L,
                AutoToolSwapAction.SWAP.wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.SWAPPED.wireCode(), 0, 9, 5L, 0L, true));
        assertPendingIntent(state, intent, 3L);
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

    @Test
    public void malformedDedicatedPhaseFieldsHaveNoEffect() {
        AutoToolSwapClientProtocolState state = openState(53L, 1L);
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 53L, 1L,
                ChainPhase.ARMED.ordinal(), 0, 1L, false));
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION + 1, 53L, 1L,
                ChainPhase.ARMED.ordinal(), 0, 1L, true));
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 53L, 1L,
                -1, 0, 1L, true));
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 53L, 1L,
                ChainPhase.values().length, 0, 1L, true));
        Assert.assertNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 53L, 1L,
                ChainPhase.ARMED.ordinal(), 0, -1L, true));
        Assert.assertEquals(0L, state.snapshot().lastPhaseSequence());
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.OPEN, state.snapshot().phase());

        Assert.assertNotNull(state.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 53L, 1L,
                ChainPhase.ARMED.ordinal(), 0, 1L, true));
        Assert.assertEquals(1L, state.snapshot().lastPhaseSequence());
    }

    private static AutoToolSwapClientProtocolState openState(long roundId, long nextActionSequence) {
        AutoToolSwapClientProtocolState state = new AutoToolSwapClientProtocolState(sequence(roundId + 100L));
        long nonce = state.beginRound();
        Assert.assertNotNull(roundResult(state, nonce, roundId, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.OPEN, nextActionSequence, 0L));
        return state;
    }

    private static AutoToolSwapClientProtocolState frozenState(long roundId) {
        AutoToolSwapClientProtocolState state = openState(roundId, 1L);
        AutoToolSwapIntent freeze = state.beginControlAction(AutoToolSwapAction.FREEZE);
        Assert.assertNotNull(actionResult(state, freeze, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FROZEN, 2L, 1L, AutoToolSwapAction.FREEZE, 0, 0));
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.CLOSING, state.snapshot().phase());
        return state;
    }

    private static void assertWaiting(AutoToolSwapClientProtocolState state, long nonce) {
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.WAIT_ROUND, state.snapshot().phase());
        Assert.assertEquals(nonce, state.snapshot().pendingNonce());
        Assert.assertEquals(AutoToolSwapProtocol.NO_SERVER_ROUND_ID, state.snapshot().serverRoundId());
        Assert.assertEquals(AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE,
                state.snapshot().nextActionSequence());
        Assert.assertNull(state.snapshot().serverRoundState());
    }

    private static void assertPendingIntent(AutoToolSwapClientProtocolState state,
            AutoToolSwapIntent intent, long nextActionSequence) {
        Assert.assertSame(intent, state.inFlightIntent());
        Assert.assertEquals(nextActionSequence, state.snapshot().nextActionSequence());
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.OPEN, state.snapshot().phase());
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, state.snapshot().serverRoundState());
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
