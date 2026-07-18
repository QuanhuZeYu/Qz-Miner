package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ActionResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ConfigEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.Effect;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.EffectResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.KeyStateEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.LocalBlockDestroyedEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ResetEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundPhaseEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.TickEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.TakeoverRequestEvent;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 单一 reducer 的 cycle、协议归因、库存双门和重传表驱动合同。 */
public class AutoToolSwapClientReducerTest {

    @Test
    public void normalTraceKeepsAppliedAndInventoryAsIndependentGatesThenRestoresAndCloses() {
        AutoToolSwapClientReducer reducer = reducer(11L);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, round.type());
        submit(reducer, round);
        acceptRound(reducer, 11L, 71L, 1L);

        Effect captureSwap = only(reducer.reduce(new TickEvent(context(0L, restored()), true)));
        AutoToolSwapIntent swap = captureAndSubmit(reducer, captureSwap, context(0L, restored()));
        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        Assert.assertTrue(reducer.isInventorySyncPending());
        reducer.reduce(new TickEvent(context(1L, restored()), true));
        Assert.assertTrue("APPLIED 不等于原版库存已可见", reducer.isInventorySyncPending());
        reducer.reduce(new TickEvent(context(2L, swapped()), true));
        Assert.assertFalse(reducer.isInventorySyncPending());
        Assert.assertTrue(reducer.hasSwapExpectation());

        reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 71L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 3L, true));
        Effect captureRestore = only(reducer.reduce(new TickEvent(context(3L, swapped()), true)));
        AutoToolSwapIntent restore = captureAndSubmit(reducer, captureRestore, context(3L, swapped()));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        settle(reducer, restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        reducer.reduce(new TickEvent(context(4L, restored()), true));
        Effect close = only(reducer.reduce(new TickEvent(context(5L, restored()), true)));
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.intent().action());
    }

    @Test
    public void previewInvalidationIsPureValueAndAppearsOnlyOnFirstVerifiedLayout() {
        AutoToolSwapClientReducer reducer = reducer(12L);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(reducer, round);
        acceptRound(reducer, 12L, 72L, 1L);
        AutoToolSwapIntent swap = captureAndSubmit(reducer,
                only(reducer.reduce(new TickEvent(context(0L, restored()), true))),
                context(0L, restored()));

        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        Assert.assertTrue("APPLIED 但旧布局仍可见时不得刷新",
                reducer.reduce(new TickEvent(context(1L, restored()), true)).isEmpty());
        Effect invalidation = only(reducer.reduce(new TickEvent(context(2L, swapped()), true)));
        Assert.assertEquals(Effect.Type.PREVIEW_INVALIDATE, invalidation.type());
        Assert.assertEquals(1L, invalidation.cycleGeneration());
        Assert.assertEquals(72L, invalidation.serverRoundId());
        Assert.assertEquals(swap.actionSequence(), invalidation.actionSequence());
        Assert.assertEquals(AutoToolSwapAction.SWAP, invalidation.action());
        Assert.assertTrue("同一布局重复观察不得重复刷新",
                reducer.reduce(new TickEvent(context(3L, swapped()), true)).isEmpty());

        AutoToolSwapClientReducer rejected = reducer(13L);
        Effect rejectedRound = only(rejected.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(rejected, rejectedRound);
        acceptRound(rejected, 13L, 73L, 1L);
        AutoToolSwapIntent rejectedSwap = captureAndSubmit(rejected,
                only(rejected.reduce(new TickEvent(context(0L, restored()), true))),
                context(0L, restored()));
        settle(rejected, rejectedSwap, AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.OPEN);
        Assert.assertTrue(rejected.reduce(new TickEvent(context(1L, swapped()), true)).isEmpty());
    }

    @Test
    public void roundAndActionIdentityRejectStaleTuplesAndRetransmitSameImmutablePayloadOnce() {
        AutoToolSwapClientReducer reducer = reducer(21L);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(reducer, round);
        reducer.reduce(new RoundResultEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 20L, 80L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true));
        Assert.assertTrue(reducer.isRoundPending());
        Effect retryRound = tickUntilEffect(reducer, 21, restored());
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, retryRound.type());
        Assert.assertEquals(21L, retryRound.clientNonce());
        Assert.assertTrue(retryRound.retry());
        submit(reducer, retryRound);
        acceptRound(reducer, 21L, 81L, 4L);

        Effect capture = only(reducer.reduce(new TickEvent(context(21L, restored()), true)));
        AutoToolSwapIntent swap = captureAndSubmit(reducer, capture, context(21L, restored()));
        reducer.reduce(new ActionResultEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 81L,
                swap.actionSequence(), AutoToolSwapAction.FREEZE.wireCode(),
                AutoToolSwapResultCode.APPLIED.wireCode(), AutoToolSwapRoundState.SWAPPED.wireCode(),
                swap.anchorSlot(), swap.candidateSlot(), swap.actionSequence() + 1L, 1L, true));
        Assert.assertSame(swap, reducer.inFlightIntent());
        Effect retryIntent = tickUntilEffect(reducer, 21, restored());
        Assert.assertSame(swap, retryIntent.intent());
        Assert.assertTrue(retryIntent.retry());
    }

    @Test
    public void naturalFinishedCloseDefersFreshRoundButReleaseGateAndResetDisqualifyIt() {
        AutoToolSwapClientReducer natural = openWithoutCandidate(31L, 91L);
        natural.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 91L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true));
        Effect close = only(natural.reduce(new TickEvent(context(1L, noCandidate()), true)));
        submit(natural, close);
        settle(natural, close.intent(), AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, natural.state());
        Assert.assertEquals("S2C callback 内无 C2S", 0,
                natural.reduce(new RoundResultEvent(0, 0L, 0L, 0, 0, 0L, 0L, false)).size());
        List<Effect> rearm = natural.reduce(new TickEvent(context(2L, noCandidate()), true));
        Effect secondRound = only(rearm);
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, secondRound.type());
        List<Effect> fresh = natural.reduce(new EffectResultEvent(secondRound, true, null));
        Assert.assertEquals(Effect.Type.FRESH_KEY, only(fresh).type());
        Assert.assertTrue(secondRound.clientNonce() > 31L);

        AutoToolSwapClientReducer released = openWithoutCandidate(41L, 101L);
        released.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 101L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true));
        Effect releasedClose = only(released.reduce(new TickEvent(context(1L, noCandidate()), true)));
        submit(released, releasedClose);
        released.reduce(new KeyStateEvent(false, context(2L, noCandidate())));
        settle(released, releasedClose.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED);
        Assert.assertTrue(released.reduce(new TickEvent(context(3L, noCandidate()), true)).isEmpty());

        natural.reduce(new ResetEvent());
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, natural.state());
        Assert.assertEquals(0L, natural.serverRoundId());
        Assert.assertFalse(natural.isKeyDown());
    }

    @Test
    public void nullReleaseKeepsRoundAndLedgerUntilRestoreCloseWhileNullRiseIsIgnored() {
        AutoToolSwapClientReducer reducer = reducer(42L, 43L);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(reducer, round);
        acceptRound(reducer, 42L, 142L, 1L);
        AutoToolSwapIntent swap = captureAndSubmit(reducer,
                only(reducer.reduce(new TickEvent(context(0L, restored()), true))),
                context(0L, restored()));
        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        reducer.reduce(new TickEvent(context(1L, swapped()), true));

        Effect failedRestoreCapture = only(reducer.reduce(new KeyStateEvent(false, null)));
        Assert.assertFalse(reducer.isKeyDown());
        Assert.assertEquals(142L, reducer.serverRoundId());
        Assert.assertTrue(reducer.hasSwapExpectation());
        Assert.assertEquals(AutoToolSwapClientReducer.State.RESTORING, reducer.state());
        Assert.assertTrue(reducer.reduce(new EffectResultEvent(
                failedRestoreCapture, false, null)).isEmpty());

        Effect retryRestoreCapture = only(reducer.reduce(
                new TickEvent(context(2L, swapped()), false)));
        AutoToolSwapIntent restore = captureAndSubmit(reducer, retryRestoreCapture,
                context(2L, swapped()));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        settle(reducer, restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        List<Effect> restoredEffects = reducer.reduce(new TickEvent(context(3L, restored()), false));
        assertPreviewInvalidation(effectOfType(restoredEffects, Effect.Type.PREVIEW_INVALIDATE),
                AutoToolSwapAction.RESTORE);
        Effect close = onlyOfType(restoredEffects, Effect.Type.SEND_INTENT);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.intent().action());
        submit(reducer, close);
        settle(reducer, close.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED);

        Assert.assertEquals(0L, reducer.serverRoundId());
        Effect nextRound = only(reducer.reduce(new KeyStateEvent(true, context(4L, restored()))));
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, nextRound.type());
        Assert.assertEquals(43L, nextRound.clientNonce());

        AutoToolSwapClientReducer nullRise = reducer(44L);
        Assert.assertTrue(nullRise.reduce(new KeyStateEvent(true, null)).isEmpty());
        Assert.assertFalse(nullRise.isKeyDown());
        Assert.assertEquals(0L, nullRise.serverRoundId());

        AutoToolSwapClientReducer lifecycle = completedSwap(45L, 145L);
        lifecycle.reduce(new ResetEvent());
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, lifecycle.state());
        Assert.assertFalse(lifecycle.isKeyDown());
        Assert.assertEquals(0L, lifecycle.serverRoundId());
        Assert.assertFalse(lifecycle.hasSwapExpectation());
    }

    @Test
    public void nullReleaseOverridesNaturalRearmAndClosesRoundWithoutLedger() {
        AutoToolSwapClientReducer reducer = openWithoutCandidate(46L, 146L);
        reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 146L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true));

        Effect close = only(reducer.reduce(new KeyStateEvent(false, null)));

        Assert.assertFalse(reducer.isKeyDown());
        Assert.assertEquals(146L, reducer.serverRoundId());
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.intent().action());
        submit(reducer, close);
        settle(reducer, close.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, reducer.state());
        Assert.assertTrue(reducer.reduce(new TickEvent(context(2L, noCandidate()), true)).isEmpty());
    }

    @Test
    public void guiAndReanchorRestoreWithinRoundWhileRejectOrThirdLayoutAbandonsSafely() {
        AutoToolSwapClientReducer gui = completedSwap(51L, 111L);
        Effect guiCapture = only(gui.reduce(new TickEvent(context(3L, true, swapped()), true)));
        AutoToolSwapIntent guiRestore = captureAndSubmit(gui, guiCapture, context(3L, swapped()));
        settle(gui, guiRestore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.OPEN);
        gui.reduce(new TickEvent(context(4L, restored()), true));
        Assert.assertEquals(111L, gui.serverRoundId());
        Assert.assertEquals(AutoToolSwapClientReducer.State.PREPARING, gui.state());

        AutoToolSwapClientReducer reanchor = completedSwap(61L, 121L);
        Effect reanchorCapture = only(reanchor.reduce(new TickEvent(context(3L, 1, swapped()), true)));
        AutoToolSwapIntent restore = captureAndSubmit(reanchor, reanchorCapture, context(3L, swapped()));
        settle(reanchor, restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.OPEN);
        reanchor.reduce(new TickEvent(context(4L, 1, reanchoredRestored()), true));
        Assert.assertEquals(121L, reanchor.serverRoundId());

        AutoToolSwapClientReducer rejected = reducer(71L);
        submit(rejected, only(rejected.reduce(new KeyStateEvent(true, context(0L, restored())))));
        rejected.reduce(new RoundResultEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 71L, 0L,
                AutoToolSwapResultCode.REJECTED.wireCode(), AutoToolSwapRoundState.PENDING_KEY.wireCode(),
                1L, 0L, true));
        Assert.assertEquals(AutoToolSwapClientReducer.State.WAIT_RELEASE, rejected.state());
        Assert.assertFalse(rejected.hasSwapExpectation());

        AutoToolSwapClientReducer thirdLayout = completedSwap(81L, 131L);
        thirdLayout.reduce(new KeyStateEvent(false, context(3L, swapped())));
        Effect capture = only(thirdLayout.reduce(new TickEvent(context(4L, swapped()), false)));
        Assert.assertTrue(thirdLayout.reduce(new EffectResultEvent(capture, true,
                context(4L, third()))).isEmpty());
        Effect abandonEffect = only(thirdLayout.reduce(new TickEvent(context(5L, third()), false)));
        AutoToolSwapIntent abandon = abandonEffect.intent();
        Assert.assertEquals(AutoToolSwapAction.ABANDON, abandon.action());
        Assert.assertEquals(0, abandon.anchorSlot());
        Assert.assertEquals(5, abandon.candidateSlot());
        Assert.assertEquals(club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint.canonicalEmpty(),
                abandon.anchorContentFingerprint());
        submit(thirdLayout, abandonEffect);
        settle(thirdLayout, abandon, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, thirdLayout.state());
        Assert.assertFalse(thirdLayout.hasSwapExpectation());
        Assert.assertFalse(thirdLayout.isOrphaned());
    }

    @Test
    public void roleLeaseAllowsDynamicRestoreAndNaturalAbandonDefersFreshRound() {
        AutoToolSwapClientReducer dynamic = completedSwap(82L, 132L);
        dynamic.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 132L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 2L, true));
        Effect captureRestore = only(dynamic.reduce(new TickEvent(context(3L, swappedAnchorChanged()), true)));
        AutoToolSwapIntent restore = captureAndSubmit(dynamic, captureRestore,
                context(3L, swappedAnchorChanged()));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        Assert.assertFalse(dynamic.isOrphaned());

        AutoToolSwapClientReducer abandonReducer = reducer(83L, 84L);
        Effect round = only(abandonReducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(abandonReducer, round);
        acceptRound(abandonReducer, 83L, 133L, 1L);
        AutoToolSwapIntent swap = captureAndSubmit(abandonReducer,
                only(abandonReducer.reduce(new TickEvent(context(0L, restored()), true))),
                context(0L, restored()));
        settle(abandonReducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        abandonReducer.reduce(new TickEvent(context(1L, swapped()), true));
        abandonReducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 133L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 2L, true));
        Effect abandonCapture = only(abandonReducer.reduce(new TickEvent(context(2L, third()), true)));
        Assert.assertTrue(abandonReducer.reduce(new EffectResultEvent(abandonCapture, true,
                context(2L, third()))).isEmpty());
        Effect abandonEffect = only(abandonReducer.reduce(new TickEvent(context(3L, third()), true)));
        submit(abandonReducer, abandonEffect);
        settle(abandonReducer, abandonEffect.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, abandonReducer.state());
        Effect nextRound = only(abandonReducer.reduce(new TickEvent(context(4L, restored()), true)));
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, nextRound.type());
        Assert.assertEquals(84L, nextRound.clientNonce());
    }

    @Test
    public void emptyOriginalAnchorRestoresOccupiedCandidateInsteadOfAbandoning() {
        AutoToolSwapClientReducer reducer = reducer(86L);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, emptyRestored()))));
        submit(reducer, round);
        acceptRound(reducer, 86L, 136L, 1L);
        AutoToolSwapIntent swap = captureAndSubmit(reducer,
                only(reducer.reduce(new TickEvent(context(0L, emptyRestored()), true))),
                context(0L, emptyRestored()));
        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        reducer.reduce(new TickEvent(context(1L, emptySwappedWithOccupant()), true));

        Effect captureRestore = only(reducer.reduce(
                new KeyStateEvent(false, context(2L, emptySwappedWithOccupant()))));
        AutoToolSwapIntent restore = captureAndSubmit(reducer, captureRestore,
                context(2L, emptySwappedWithOccupant()));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        settle(reducer, restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        reducer.reduce(new TickEvent(context(3L, emptyRestoreResult()), false));
        Effect close = only(reducer.reduce(new TickEvent(context(4L, emptyRestoreResult()), false)));

        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.intent().action());
        Assert.assertFalse(reducer.hasSwapExpectation());
        Assert.assertFalse(reducer.isOrphaned());
    }

    @Test
    public void abandonRejectionIsARealOrphan() {
        AutoToolSwapClientReducer reducer = completedSwap(85L, 135L);
        reducer.reduce(new KeyStateEvent(false, context(2L, swapped())));
        Effect capture = only(reducer.reduce(new TickEvent(context(3L, swapped()), false)));
        reducer.reduce(new EffectResultEvent(capture, true, context(3L, third())));
        Effect abandon = only(reducer.reduce(new TickEvent(context(4L, third()), false)));
        submit(reducer, abandon);
        settle(reducer, abandon.intent(), AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.CLOSING);
        Assert.assertTrue(reducer.isOrphaned());
    }

    @Test
    public void reducerOwnsOneExplicitStateAndNamedAuthorityContexts() {
        Assert.assertEquals(1, AutoToolSwapClientReducer.State.class.getDeclaredFields().length
                - AutoToolSwapClientReducer.State.values().length);
        String source = source("src/main/java/club/heiqi/qz_miner/client/toolswap/AutoToolSwapClientReducer.java");
        Assert.assertTrue(source.contains("class RoundContext"));
        Assert.assertTrue(source.contains("class SwapExpectation"));
        Assert.assertTrue(source.contains("class PendingTransmission"));
        Assert.assertTrue(source.contains("enum CloseCause"));
        Assert.assertFalse(source.contains("ToolSwap" + "TransactionState"));
    }

    @Test
    public void preEdgeDestroyLatchRequiresTheSameWorldGeneration() {
        AutoToolSwapClientReducer reducer = reducer(91L);
        reducer.reduce(new LocalBlockDestroyedEvent(true, 7L));
        ToolSwapLightContext light = new ToolSwapLightContext(0L, true, false, false, true, 0);

        Assert.assertEquals(ToolSwapCapturePlan.FULL,
                reducer.capturePlanForKeyState(true, light, 8L));
        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForKeyState(true, light, 7L));
    }

    @Test
    public void frozenSettlementBeforeActivePhasesKeepsSwapWithoutASecondFreezeOrClose() {
        final List<String> diagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer reducer = diagnosticReducer(92L, diagnostics);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(reducer, round);
        acceptRound(reducer, 92L, 192L, 1L);
        Effect capture = only(reducer.reduce(new TickEvent(context(0L, restored()), true)));
        AutoToolSwapIntent swap = captureAndSubmit(reducer, capture, context(0L, restored()));
        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        reducer.reduce(new TickEvent(context(1L, swapped()), true));

        Effect freezeEffect = only(reducer.reduce(new LocalBlockDestroyedEvent(true)));
        AutoToolSwapIntent freeze = freezeEffect.intent();
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.action());
        submit(reducer, freezeEffect);
        settle(reducer, freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);

        assertActivePhasesDoNotDriveAnotherIntent(reducer, 192L, 1L, 2L);
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, reducer.state());
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, reducer.serverRoundState());
        Assert.assertTrue(reducer.hasSwapExpectation());
        Assert.assertFalse(diagnostics.toString(), containsDiagnosticReason(diagnostics, "protocol-orphan"));
    }

    @Test
    public void activePhasesBeforeFreezeSettlementShareTheSingleInFlightFreeze() {
        AutoToolSwapClientReducer reducer = completedSwap(93L, 193L);
        Assert.assertTrue(reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 193L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 2L, true)).isEmpty());
        Effect freezeEffect = only(reducer.reduce(new TickEvent(context(2L, swapped()), true)));
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freezeEffect.intent().action());
        AutoToolSwapIntent freeze = freezeEffect.intent();
        submit(reducer, freezeEffect);

        Assert.assertTrue(reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 193L, 2L,
                ChainPhase.RUNNING.ordinal(), 1, 3L, true)).isEmpty());
        Assert.assertTrue(reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, 193L, 3L,
                ChainPhase.FINISHING.ordinal(), 1, 4L, true)).isEmpty());
        settle(reducer, freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);

        Assert.assertTrue(reducer.reduce(new TickEvent(context(3L, swapped()), true)).isEmpty());
        Assert.assertEquals(3L, reducer.nextActionSequence());
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, reducer.state());
        Assert.assertTrue(reducer.hasSwapExpectation());
    }

    @Test
    public void restoreReasonDiagnosticContainsRoundStateSlotsAndGuiAndRepeatedTickIsBounded() {
        final List<String> diagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer reducer = diagnosticReducer(101L, diagnostics);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(reducer, round);
        acceptRound(reducer, 101L, 201L, 1L);
        Effect capture = only(reducer.reduce(new TickEvent(context(0L, restored()), true)));
        AutoToolSwapIntent swap = captureAndSubmit(reducer, capture, context(0L, restored()));
        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        reducer.reduce(new TickEvent(context(1L, swapped()), true));

        reducer.reduce(new TickEvent(context(2L, true, swapped()), true));
        int boundedCount = diagnostics.size();
        for (int tick = 3; tick < 200; tick++) {
            reducer.reduce(new TickEvent(context(tick, true, swapped()), true));
        }

        Assert.assertEquals("同 round 同类重复 tick 不得继续生成诊断", boundedCount, diagnostics.size());
        String guiReason = diagnosticWithReason(diagnostics, "gui-open");
        Assert.assertTrue(guiReason.startsWith("[AutoToolSwapClientDiag] reason=gui-open"));
        Assert.assertTrue(guiReason.contains("clientTick="));
        Assert.assertTrue(guiReason.contains("nonce=101"));
        Assert.assertTrue(guiReason.contains("serverRoundId=201"));
        Assert.assertTrue(guiReason.contains("state="));
        Assert.assertTrue(guiReason.contains("selectedHotbarSlot=0"));
        Assert.assertTrue(guiReason.contains("anchorSlot=0"));
        Assert.assertTrue(guiReason.contains("guiOpen=true"));
        Assert.assertTrue(guiReason.contains("round.phase="));
        Assert.assertTrue(guiReason.contains("round.lastPhaseSequence="));
    }

    @Test
    public void diagnosticReasonVocabularyCoversAllRestoreAndCloseSources() {
        String source = source("src/main/java/club/heiqi/qz_miner/client/toolswap/AutoToolSwapClientReducer.java");
        Assert.assertTrue(source.contains("GUI_OPEN(\"gui-open\")"));
        Assert.assertTrue(source.contains("SELECTED_SLOT_REANCHOR(\"selected-slot-reanchor\")"));
        Assert.assertTrue(source.contains("RELEASE(\"release\")"));
        Assert.assertTrue(source.contains("NATURAL_IDLE(\"natural-idle\")"));
        Assert.assertTrue(source.contains("CONFIG_DISABLED(\"config-disabled\")"));
        Assert.assertTrue(source.contains("PROTOCOL_ORPHAN(\"protocol-orphan\")"));
        Assert.assertTrue(source.contains("MAX_DIAGNOSTIC_MESSAGES = 64"));
    }

    @Test
    public void silentCycleAndSelectionDecisionsAreDiagnosedWithoutChangingEffectsOrState() {
        List<String> disabledDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer disabled = diagnosticReducer(false, true, 301L, disabledDiagnostics);
        for (int cycle = 0; cycle < 70; cycle++) {
            Assert.assertTrue(disabled.reduce(new KeyStateEvent(true,
                    context(cycle, noCandidate()))).isEmpty());
            Assert.assertEquals(AutoToolSwapClientReducer.State.WAIT_RELEASE, disabled.state());
            Assert.assertTrue(disabled.reduce(new KeyStateEvent(false,
                    context(cycle, noCandidate()))).isEmpty());
            Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, disabled.state());
        }
        Assert.assertEquals("每个新 cycle 必须恢复诊断预算", 70,
                diagnosticCount(disabledDiagnostics, "cycle-disabled"));

        List<String> ineligibleDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer ineligible = diagnosticReducer(true, true, 302L, ineligibleDiagnostics);
        ToolSwapContext creative = new ToolSwapContext(0L, true, true, false, false,
                true, 0, noCandidate(), target(1, 0));
        Assert.assertTrue(ineligible.reduce(new KeyStateEvent(true, creative)).isEmpty());
        Assert.assertEquals(AutoToolSwapClientReducer.State.WAIT_RELEASE, ineligible.state());
        Assert.assertTrue(containsDiagnosticReason(ineligibleDiagnostics, "cycle-ineligible"));

        List<String> heldDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer held = diagnosticReducer(true, true, 303L, heldDiagnostics);
        Effect heldRound = only(held.reduce(new KeyStateEvent(true, context(0L, swapped()))));
        submit(held, heldRound);
        acceptRound(held, 303L, 403L, 1L);
        Assert.assertTrue(held.reduce(new TickEvent(context(0L, swapped()), true)).isEmpty());
        Assert.assertEquals(AutoToolSwapClientReducer.State.PREPARING, held.state());
        Assert.assertTrue(containsDiagnosticReason(heldDiagnostics, "held-usable"));

        List<String> noCandidateDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer noCandidate = diagnosticReducer(true, true, 304L, noCandidateDiagnostics);
        Effect emptyRound = only(noCandidate.reduce(new KeyStateEvent(true, context(0L, noCandidate()))));
        submit(noCandidate, emptyRound);
        acceptRound(noCandidate, 304L, 404L, 1L);
        Assert.assertTrue(noCandidate.reduce(new TickEvent(context(0L, noCandidate()), true)).isEmpty());
        Assert.assertEquals(AutoToolSwapClientReducer.State.PREPARING, noCandidate.state());
        Assert.assertTrue(containsDiagnosticReason(noCandidateDiagnostics, "no-candidate"));
    }

    @Test
    public void takeoverDeclineAndIgnoredReasonsAreBoundedAndKeepProtocolEffects() {
        List<String> disabledDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer disabled = frozenForTakeover(311L, 411L, false, disabledDiagnostics);
        submitTakeoverRequest(disabled, 411L, 2L, 4);
        List<Effect> disabledDeclines = disabled.reduce(new TickEvent(context(2L, noCandidate()), true));
        Assert.assertEquals("保持既有 prepare+drive effect 结果", 2, disabledDeclines.size());
        for (Effect disabledDecline : disabledDeclines) {
            Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER, disabledDecline.intent().action());
        }
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, disabled.state());
        Assert.assertTrue(containsDiagnosticReason(disabledDiagnostics, "takeover-disabled"));

        List<String> unsafeDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer unsafe = frozenForTakeover(312L, 412L, true, unsafeDiagnostics);
        submitTakeoverRequest(unsafe, 412L, 2L, 4);
        ToolSwapContext unsafeContext = new ToolSwapContext(2L, true, false, false, false,
                true, 0, noCandidate(), target(1, 0));
        Effect unsafeDecline = only(unsafe.reduce(new TickEvent(unsafeContext, true)));
        Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER, unsafeDecline.intent().action());
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, unsafe.state());
        Assert.assertTrue(containsDiagnosticReason(unsafeDiagnostics, "takeover-inventory-unsafe"));

        List<String> emptyDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer empty = frozenForTakeover(313L, 413L, true, emptyDiagnostics);
        submitTakeoverRequest(empty, 413L, 2L, 4);
        Effect emptyDecline = only(empty.reduce(new TickEvent(context(2L, noCandidate()), true)));
        Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER, emptyDecline.intent().action());
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, empty.state());
        Assert.assertTrue(containsDiagnosticReason(emptyDiagnostics, "takeover-no-candidate"));

        List<String> ignoredDiagnostics = new ArrayList<String>();
        AutoToolSwapClientReducer ignored = frozenForTakeover(314L, 414L, true, ignoredDiagnostics);
        submitTakeoverRequest(ignored, 999L, 2L, 4);
        submitTakeoverRequest(ignored, 998L, 2L, 4);
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, ignored.state());
        Assert.assertEquals("同 cycle 同类忽略只记录一次", 1,
                diagnosticCount(ignoredDiagnostics, "takeover-request-ignored"));
    }

    @Test
    public void changedTargetRestoresOldLedgerBeforeMatchingLatestTarget() {
        AutoToolSwapClientReducer reducer = completedSwap(111L, 211L);

        Effect restoreCapture = only(reducer.reduce(new TickEvent(
                context(2L, target(2, 0), swapped()), true)));
        AutoToolSwapIntent restore = captureAndSubmit(reducer, restoreCapture,
                context(2L, target(2, 0), swapped()));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        settle(reducer, restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.OPEN);
        assertPreviewInvalidation(reducer.reduce(new TickEvent(
                context(3L, target(2, 0), restored()), true)), AutoToolSwapAction.RESTORE);

        Effect nextSwapCapture = only(reducer.reduce(new TickEvent(
                context(4L, target(2, 0), restoredB()), true)));
        AutoToolSwapIntent nextSwap = captureAndSubmit(reducer, nextSwapCapture,
                context(4L, target(2, 0), restoredB()));
        Assert.assertEquals(AutoToolSwapAction.SWAP, nextSwap.action());
        Assert.assertEquals(7, nextSwap.candidateSlot());
        Assert.assertEquals("唯一 ledger 清账前不得发送第二个 SWAP", 3L, nextSwap.actionSequence());
    }

    @Test
    public void latestTargetWinsWhileRestoreIsInFlightAndReturnCanOnlyCancelUnsubmittedRestore() {
        AutoToolSwapClientReducer reducer = completedSwap(112L, 212L);
        Effect unsubmitted = only(reducer.reduce(new TickEvent(
                context(2L, target(2, 0), swapped()), true)));
        Assert.assertEquals(Effect.Type.CAPTURE, unsubmitted.type());
        Assert.assertTrue("回到 A 可取消尚未提交的目标专用恢复",
                reducer.reduce(new TickEvent(context(3L, target(1, 0), swapped()), true)).isEmpty());
        Assert.assertEquals(AutoToolSwapClientReducer.State.PREPARING, reducer.state());

        Effect secondCapture = only(reducer.reduce(new TickEvent(
                context(4L, target(2, 0), swapped()), true)));
        AutoToolSwapIntent restore = captureAndSubmit(reducer, secondCapture,
                context(4L, target(2, 0), swapped()));
        Assert.assertTrue(reducer.reduce(new TickEvent(
                context(5L, target(3, 0), swapped()), true)).isEmpty());
        Assert.assertTrue(reducer.reduce(new TickEvent(
                context(6L, target(1, 0), swapped()), true)).isEmpty());

        settle(reducer, restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.OPEN);
        reducer.reduce(new TickEvent(context(7L, target(1, 0), restored()), true));
        Effect rematchA = only(reducer.reduce(new TickEvent(
                context(8L, target(1, 0), restored()), true)));
        Assert.assertEquals("已发送 RESTORE 不得因回到 A 被取消", Effect.Type.CAPTURE, rematchA.type());
    }

    @Test
    public void swapInFlightSettlesThenRestoresForLatestTarget() {
        AutoToolSwapClientReducer reducer = reducer(113L);
        Effect round = only(reducer.reduce(new KeyStateEvent(true,
                context(0L, target(1, 0), restored()))));
        submit(reducer, round);
        acceptRound(reducer, 113L, 213L, 1L);
        AutoToolSwapIntent swap = captureAndSubmit(reducer,
                only(reducer.reduce(new TickEvent(context(0L, target(1, 0), restored()), true))),
                context(0L, target(1, 0), restored()));

        Assert.assertTrue(reducer.reduce(new TickEvent(
                context(1L, target(2, 0), restored()), true)).isEmpty());
        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        List<Effect> swappedEffects = reducer.reduce(new TickEvent(
                context(2L, target(3, 0), swapped()), true));
        assertPreviewInvalidation(effectOfType(swappedEffects, Effect.Type.PREVIEW_INVALIDATE),
                AutoToolSwapAction.SWAP);
        Effect restoreCapture = onlyOfType(swappedEffects, Effect.Type.CAPTURE);
        Assert.assertEquals(Effect.Type.CAPTURE, restoreCapture.type());
        AutoToolSwapIntent restore = captureAndSubmit(reducer, restoreCapture,
                context(2L, target(3, 0), swapped()));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
    }

    @Test
    public void absentNeedsTwoTicksAndNoLedgerOnlyRearmsWhenTargetReturns() {
        AutoToolSwapClientReducer swappedReducer = completedSwap(114L, 214L);
        Assert.assertTrue(swappedReducer.reduce(new TickEvent(
                context(2L, ToolSwapTargetIdentity.ABSENT, swapped()), true)).isEmpty());
        Effect restore = only(swappedReducer.reduce(new TickEvent(
                context(3L, ToolSwapTargetIdentity.ABSENT, swapped()), true)));
        Assert.assertEquals(Effect.Type.CAPTURE, restore.type());

        AutoToolSwapClientReducer noLedger = openWithoutCandidate(115L, 215L);
        noLedger.reduce(new TickEvent(context(0L, target(1, 0), noCandidate()), true));
        Assert.assertTrue(noLedger.reduce(new TickEvent(
                context(1L, ToolSwapTargetIdentity.ABSENT, noCandidate()), true)).isEmpty());
        Assert.assertTrue(noLedger.reduce(new TickEvent(
                context(2L, ToolSwapTargetIdentity.ABSENT, noCandidate()), true)).isEmpty());
        Effect returned = only(noLedger.reduce(new TickEvent(
                context(3L, target(2, 1), restoredB()), true)));
        Assert.assertEquals(Effect.Type.CAPTURE, returned.type());
    }

    @Test
    public void stableAbsentCapturePlanStaysNonePastWatermarkAndPresentTargetRestoresCadence() {
        AutoToolSwapClientReducer reducer = openWithoutCandidate(117L, 217L);
        ToolSwapTargetIdentity firstTarget = target(1, 0);

        Assert.assertEquals(ToolSwapCapturePlan.FULL,
                reducer.capturePlanForTick(light(0L, firstTarget), true));
        reducer.reduce(new TickEvent(context(0L, firstTarget, noCandidate()), true));

        for (long tick : new long[] {1L, 2L, 10L, 11L, 99L}) {
            Assert.assertEquals("稳定 ABSENT 不得越过水位后重复 FULL，tick=" + tick,
                    ToolSwapCapturePlan.NONE,
                    reducer.capturePlanForTick(light(tick, ToolSwapTargetIdentity.ABSENT), true));
            reducer.reduce(new TickEvent(
                    context(tick, ToolSwapTargetIdentity.ABSENT, ToolSwapInventorySnapshot.none()), true));
        }

        ToolSwapTargetIdentity returnedTarget = target(2, 1);
        Assert.assertEquals("有效目标恢复须立即 FULL", ToolSwapCapturePlan.FULL,
                reducer.capturePlanForTick(light(100L, returnedTarget), true));
        reducer.reduce(new TickEvent(context(100L, returnedTarget, noCandidate()), true));
        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForTick(light(109L, returnedTarget), true));
        Assert.assertEquals("稳定有效目标保留十 tick 周期 FULL", ToolSwapCapturePlan.FULL,
                reducer.capturePlanForTick(light(110L, returnedTarget), true));
    }

    @Test
    public void closeAndFreezeHardStopOrdinaryFullButKeepProtectedLedgerCapture() {
        ToolSwapTargetIdentity firstTarget = target(1, 0);
        ToolSwapTargetIdentity changedTarget = target(2, 1);

        AutoToolSwapClientReducer released = openWithoutCandidate(118L, 218L);
        released.reduce(new TickEvent(context(0L, firstTarget, noCandidate()), true));
        Assert.assertEquals(ToolSwapCapturePlan.FULL,
                released.capturePlanForTick(light(10L, firstTarget), true));
        Assert.assertEquals(AutoToolSwapAction.CLOSE,
                only(released.reduce(new KeyStateEvent(false,
                        context(10L, firstTarget, noCandidate())))).intent().action());
        Assert.assertEquals("松键后目标变化与到期水位均不得触发普通 FULL", ToolSwapCapturePlan.NONE,
                released.capturePlanForTick(light(10L, changedTarget), false));
        assertNoNewSwap(released,
                released.reduce(new TickEvent(context(10L, firstTarget, restored()), false)));

        AutoToolSwapClientReducer disabled = openWithoutCandidate(119L, 219L);
        disabled.reduce(new TickEvent(context(0L, firstTarget, noCandidate()), true));
        Assert.assertEquals(AutoToolSwapAction.CLOSE,
                only(disabled.reduce(new ConfigEvent(false, Collections.<ToolSelector>emptyList())))
                        .intent().action());
        Assert.assertEquals("配置关闭后不得因新目标触发普通 FULL", ToolSwapCapturePlan.NONE,
                disabled.capturePlanForTick(light(10L, changedTarget), true));
        assertNoNewSwap(disabled,
                disabled.reduce(new TickEvent(context(10L, firstTarget, restored()), true)));

        AutoToolSwapClientReducer frozen = openWithoutCandidate(120L, 220L);
        frozen.reduce(new TickEvent(context(0L, firstTarget, noCandidate()), true));
        Assert.assertEquals(AutoToolSwapAction.FREEZE,
                only(frozen.reduce(new LocalBlockDestroyedEvent(true))).intent().action());
        Assert.assertEquals("FREEZE 请求后不得因新目标触发普通 FULL", ToolSwapCapturePlan.NONE,
                frozen.capturePlanForTick(light(10L, changedTarget), true));
        assertNoNewSwap(frozen,
                frozen.reduce(new TickEvent(context(10L, firstTarget, restored()), true)));

        AutoToolSwapClientReducer restoring = completedSwap(121L, 221L);
        Effect restoreCapture = only(restoring.reduce(new KeyStateEvent(false,
                context(2L, firstTarget, swapped()))));
        Assert.assertEquals(Effect.Type.CAPTURE, restoreCapture.type());
        Assert.assertEquals("关闭期间已有 ledger 仍须优先采样受保护槽", ToolSwapCapturePlan.PROTECTED,
                restoring.capturePlanForTick(light(2L, changedTarget), false));
        Assert.assertEquals(AutoToolSwapAction.RESTORE,
                captureAndSubmit(restoring, restoreCapture,
                        context(2L, firstTarget, swapped())).action());

        AutoToolSwapClientReducer freezingLedger = completedSwap(122L, 222L);
        Assert.assertEquals(AutoToolSwapAction.FREEZE,
                only(freezingLedger.reduce(new LocalBlockDestroyedEvent(true))).intent().action());
        Assert.assertEquals("FREEZE 期间已有 ledger 仍须优先采样受保护槽", ToolSwapCapturePlan.PROTECTED,
                freezingLedger.capturePlanForTick(light(2L, changedTarget), true));
    }

    @Test
    public void stableIdentityKeepsWatermarkButMetadataChangeRematchesImmediately() {
        AutoToolSwapClientReducer reducer = openWithoutCandidate(116L, 216L);
        reducer.reduce(new TickEvent(context(0L, target(1, 0), noCandidate()), true));
        Assert.assertTrue(reducer.reduce(new TickEvent(
                context(1L, target(1, 0), restoredB()), true)).isEmpty());

        Effect changedMetadata = only(reducer.reduce(new TickEvent(
                context(2L, target(1, 1), restoredB()), true)));
        Assert.assertEquals(Effect.Type.CAPTURE, changedMetadata.type());
    }

    private static AutoToolSwapClientReducer completedSwap(long nonce, long roundId) {
        AutoToolSwapClientReducer reducer = reducer(nonce);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, restored()))));
        submit(reducer, round);
        acceptRound(reducer, nonce, roundId, 1L);
        Effect capture = only(reducer.reduce(new TickEvent(context(0L, restored()), true)));
        AutoToolSwapIntent swap = captureAndSubmit(reducer, capture, context(0L, restored()));
        settle(reducer, swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        reducer.reduce(new TickEvent(context(1L, swapped()), true));
        return reducer;
    }

    private static AutoToolSwapClientReducer openWithoutCandidate(long nonce, long roundId) {
        AutoToolSwapClientReducer reducer = reducer(nonce, nonce + 1L);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, noCandidate()))));
        submit(reducer, round);
        acceptRound(reducer, nonce, roundId, 1L);
        return reducer;
    }

    private static AutoToolSwapClientReducer reducer(final long... nonces) {
        return new AutoToolSwapClientReducer(true, Collections.emptyList(), new AutoToolSwapClientReducer.NonceAllocator() {
            private int index;
            @Override public long allocate() { return index < nonces.length ? nonces[index++] : 0L; }
        });
    }

    private static AutoToolSwapClientReducer diagnosticReducer(final long nonce,
            final List<String> diagnostics) {
        return diagnosticReducer(true, true, nonce, diagnostics);
    }

    private static AutoToolSwapClientReducer diagnosticReducer(boolean enabled, boolean takeoverEnabled,
            final long nonce, final List<String> diagnostics) {
        return new AutoToolSwapClientReducer(enabled, takeoverEnabled, Collections.emptyList(),
                new AutoToolSwapClientReducer.NonceAllocator() {
                    @Override public long allocate() { return nonce; }
                }, new AutoToolSwapClientReducer.DiagnosticSink() {
                    @Override public void log(String message) { diagnostics.add(message); }
                });
    }

    private static AutoToolSwapClientReducer frozenForTakeover(long nonce, long roundId,
            boolean takeoverEnabled, List<String> diagnostics) {
        AutoToolSwapClientReducer reducer = diagnosticReducer(true, takeoverEnabled, nonce, diagnostics);
        Effect round = only(reducer.reduce(new KeyStateEvent(true, context(0L, noCandidate()))));
        submit(reducer, round);
        acceptRound(reducer, nonce, roundId, 1L);
        reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, 1L,
                ChainPhase.RUNNING.ordinal(), 4, 1L, true));
        Effect freezeEffect = only(reducer.reduce(new TickEvent(context(1L, noCandidate()), true)));
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freezeEffect.intent().action());
        submit(reducer, freezeEffect);
        settle(reducer, freezeEffect.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FROZEN);
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, reducer.state());
        return reducer;
    }

    private static void submitTakeoverRequest(AutoToolSwapClientReducer reducer, long roundId,
            long actionSequence, int generation) {
        Assert.assertTrue(reducer.reduce(new TakeoverRequestEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                roundId, actionSequence, generation, 10, 64, 20, 42, 7,
                2L, 10L, true)).isEmpty());
    }

    private static String diagnosticWithReason(List<String> diagnostics, String reason) {
        for (String diagnostic : diagnostics) {
            if (diagnostic.contains("reason=" + reason + " ")) return diagnostic;
        }
        Assert.fail("missing diagnostic reason=" + reason + ": " + diagnostics);
        return "";
    }

    private static boolean containsDiagnosticReason(List<String> diagnostics, String reason) {
        for (String diagnostic : diagnostics) {
            if (diagnostic.contains("reason=" + reason + " ")) return true;
        }
        return false;
    }

    private static int diagnosticCount(List<String> diagnostics, String reason) {
        int count = 0;
        for (String diagnostic : diagnostics) {
            if (diagnostic.contains("reason=" + reason + " ")) count++;
        }
        return count;
    }

    private static void assertActivePhasesDoNotDriveAnotherIntent(AutoToolSwapClientReducer reducer,
            long roundId, long firstPhaseSequence, long tick) {
        ChainPhase[] phases = {ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING,
                ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING};
        for (int index = 0; index < phases.length; index++) {
            Assert.assertTrue(reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId,
                    firstPhaseSequence + index, phases[index].ordinal(), 1, tick + index, true)).isEmpty());
            Assert.assertTrue(reducer.reduce(new TickEvent(context(tick + index, swapped()), true)).isEmpty());
        }
    }

    private static void acceptRound(AutoToolSwapClientReducer reducer, long nonce, long roundId, long sequence) {
        reducer.reduce(new RoundResultEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, nonce, roundId,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                sequence, 0L, true));
    }

    private static void settle(AutoToolSwapClientReducer reducer, AutoToolSwapIntent intent,
            AutoToolSwapResultCode result, AutoToolSwapRoundState state) {
        reducer.reduce(new ActionResultEvent(AutoToolSwapProtocol.PROTOCOL_VERSION, intent.serverRoundId(),
                intent.actionSequence(), intent.action().wireCode(), result.wireCode(), state.wireCode(),
                intent.anchorSlot(), intent.candidateSlot(), intent.actionSequence() + 1L, 1L, true));
    }

    private static AutoToolSwapIntent captureAndSubmit(AutoToolSwapClientReducer reducer,
            Effect capture, ToolSwapContext context) {
        Assert.assertEquals(Effect.Type.CAPTURE, capture.type());
        Effect intent = only(reducer.reduce(new EffectResultEvent(capture, true, context)));
        Assert.assertEquals(Effect.Type.SEND_INTENT, intent.type());
        submit(reducer, intent);
        return intent.intent();
    }

    private static void submit(AutoToolSwapClientReducer reducer, Effect effect) {
        reducer.reduce(new EffectResultEvent(effect, true, null));
    }

    private static Effect tickUntilEffect(AutoToolSwapClientReducer reducer, int count,
            ToolSwapInventorySnapshot inventory) {
        List<Effect> effects = Collections.emptyList();
        for (int index = 0; index < count && effects.isEmpty(); index++) {
            effects = reducer.reduce(new TickEvent(context(reducer.clientTick(), inventory), true));
        }
        return only(effects);
    }

    private static Effect only(List<Effect> effects) {
        Assert.assertEquals("effects=" + effects.size(), 1, effects.size());
        return effects.get(0);
    }

    private static void assertPreviewInvalidation(List<Effect> effects, AutoToolSwapAction action) {
        Effect effect = only(effects);
        Assert.assertEquals(Effect.Type.PREVIEW_INVALIDATE, effect.type());
        Assert.assertEquals(action, effect.action());
    }

    private static void assertPreviewInvalidation(Effect effect, AutoToolSwapAction action) {
        Assert.assertEquals(Effect.Type.PREVIEW_INVALIDATE, effect.type());
        Assert.assertEquals(action, effect.action());
    }

    private static Effect effectOfType(List<Effect> effects, Effect.Type type) {
        return onlyOfType(effects, type);
    }

    private static Effect onlyOfType(List<Effect> effects, Effect.Type type) {
        Effect matched = null;
        for (Effect effect : effects) {
            if (effect.type() == type) {
                Assert.assertNull("duplicate effect type=" + type, matched);
                matched = effect;
            }
        }
        Assert.assertNotNull("missing effect type=" + type + " in " + effects.size(), matched);
        return matched;
    }

    private static void assertNoNewSwap(AutoToolSwapClientReducer reducer, List<Effect> effects) {
        Assert.assertFalse("硬收口后不得创建新的库存 ledger", reducer.hasSwapExpectation());
        for (Effect effect : effects) {
            Assert.assertFalse("硬收口后不得请求 SWAP 捕获", effect.type() == Effect.Type.CAPTURE);
            if (effect.intent() != null) {
                Assert.assertNotEquals("硬收口后不得发送新 SWAP", AutoToolSwapAction.SWAP,
                        effect.intent().action());
            }
        }
    }

    private static ToolSwapContext context(long tick, ToolSwapInventorySnapshot inventory) {
        return context(tick, false, inventory);
    }

    private static ToolSwapContext context(long tick, boolean gui, ToolSwapInventorySnapshot inventory) {
        return context(tick, gui, target(1, 0), inventory);
    }

    private static ToolSwapContext context(long tick, int slot, ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, false, true, true, slot, inventory, target(1, 0));
    }

    private static ToolSwapContext context(long tick, ToolSwapTargetIdentity target,
            ToolSwapInventorySnapshot inventory) {
        return context(tick, false, target, inventory);
    }

    private static ToolSwapContext context(long tick, boolean gui, ToolSwapTargetIdentity target,
            ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, gui, !gui, true, 0, inventory, target);
    }

    private static ToolSwapTargetIdentity target(int blockId, int metadata) {
        return ToolSwapTargetIdentity.present(blockId, metadata);
    }

    private static ToolSwapLightContext light(long tick, ToolSwapTargetIdentity target) {
        return new ToolSwapLightContext(tick, true, false, false, true, 0, target);
    }

    private static ToolSwapInventorySnapshot restored() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(0, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot swapped() {
        return inventory(new SlotSnapshot(0, "pick", "used"), new SlotSnapshot(5, "hand", "old"),
                tool(0, "pick", true), tool(5, "hand", false));
    }

    private static ToolSwapInventorySnapshot restoredB() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(7, "drill", "fresh"),
                tool(0, "hand", false), tool(7, "drill", true));
    }

    private static ToolSwapInventorySnapshot swappedAnchorChanged() {
        return inventory(new SlotSnapshot(0, "pick", "energy=20;damage=7"),
                new SlotSnapshot(5, "hand", "count=3;nbt=merged"),
                tool(0, "pick", true), tool(5, "hand", false));
    }

    private static ToolSwapInventorySnapshot emptyRestored() {
        return inventory(new SlotSnapshot(0, SlotSnapshot.EMPTY_ROLE_KEY, ""),
                new SlotSnapshot(5, "pick", "fresh"), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot emptySwappedWithOccupant() {
        return inventory(new SlotSnapshot(0, "pick", "used"),
                new SlotSnapshot(5, "drop", "occupied"), tool(0, "pick", true));
    }

    private static ToolSwapInventorySnapshot emptyRestoreResult() {
        return inventory(new SlotSnapshot(0, "drop", "occupied"),
                new SlotSnapshot(5, "pick", "used"), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot reanchoredRestored() {
        return inventory(new SlotSnapshot(1, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(1, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot third() {
        return inventory(new SlotSnapshot(0, "pick", "used"), new SlotSnapshot(5, "third", "other"));
    }

    private static ToolSwapInventorySnapshot noCandidate() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "empty", ""),
                tool(0, "hand", false));
    }

    private static ToolCandidate tool(int slot, String name, boolean usable) {
        return new ToolCandidate(slot, "test:" + name, 0, Arrays.asList("toolPickaxe"), usable, usable, 100);
    }

    private static ToolSwapInventorySnapshot inventory(Object... values) {
        ArrayList<SlotSnapshot> slots = new ArrayList<SlotSnapshot>();
        ArrayList<ToolCandidate> candidates = new ArrayList<ToolCandidate>();
        for (Object value : values) {
            if (value instanceof SlotSnapshot) slots.add((SlotSnapshot) value);
            if (value instanceof ToolCandidate) candidates.add((ToolCandidate) value);
        }
        return new ToolSwapInventorySnapshot(slots, candidates);
    }

    private static String source(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(new java.io.File(path).toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
    }
}
