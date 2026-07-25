package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 运行态 round、重传、精确回包与 lifecycle 合同。 */
public class AutoToolSwapClientAdapterTest {

    private Object handler;
    private Object world;
    private FakeGame game;
    private RecordingTransport transport;
    private AutoToolSwapClientAdapter adapter;

    @Before
    public void setUp() {
        handler = new Object();
        world = new Object();
        ClientConnectionLifecycle.connect(handler);
        ClientConnectionLifecycle.bindWorld(world);
        game = new FakeGame();
        transport = new RecordingTransport();
        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport);
    }

    @After
    public void tearDown() {
        ClientConnectionLifecycle.unbindWorld(world);
        ClientConnectionLifecycle.disconnect(handler);
    }

    @Test
    public void risingEdgeSendsRoundBeforeIntentAndAppliedUsesProtectedInventoryGate() {
        adapter.onChainKeyState(true);
        Assert.assertEquals(1, transport.rounds.size());
        Assert.assertTrue(transport.intents.isEmpty());

        acceptRound();
        Assert.assertTrue(transport.intents.isEmpty());
        adapter.onClientTick();
        Assert.assertEquals(1, transport.intents.size());
        AutoToolSwapIntent swap = transport.intents.get(0);
        Assert.assertEquals(AutoToolSwapAction.SWAP, swap.action());

        game.inventory = swapped();
        settle(swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        Assert.assertTrue(adapter.reducerForTests().isInventorySyncPending());
        adapter.onClientTick();
        Assert.assertFalse(adapter.reducerForTests().isInventorySyncPending());
    }

    @Test
    public void swapAndRestoreNotifyPreviewExactlyOnceAfterEachLayoutBecomesVisible() {
        final List<PreviewNotice> notices = new ArrayList<PreviewNotice>();
        adapter = adapterWithPreviewListener(notices);
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        AutoToolSwapIntent swap = transport.intents.get(0);
        settle(swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);

        adapter.onClientTick();
        Assert.assertTrue("APPLIED 未可见不得通知", notices.isEmpty());
        game.inventory = swapped();
        adapter.onClientTick();
        Assert.assertEquals(1, notices.size());
        Assert.assertEquals(AutoToolSwapAction.SWAP, notices.get(0).action);
        adapter.onClientTick();
        Assert.assertEquals(1, notices.size());

        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 3L, true);
        adapter.onClientTick();
        AutoToolSwapIntent restore = transport.intents.get(1);
        settle(restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        adapter.onClientTick();
        Assert.assertEquals("RESTORE APPLIED 未可见不得通知", 1, notices.size());
        game.inventory = restored();
        adapter.onClientTick();
        Assert.assertEquals(2, notices.size());
        Assert.assertEquals(AutoToolSwapAction.RESTORE, notices.get(1).action);
        adapter.onClientTick();
        Assert.assertEquals(2, notices.size());
    }

    @Test
    public void takeoverNotifiesPreviewOnlyWhenThreeSlotTargetLayoutIsVisible() {
        final List<PreviewNotice> notices = new ArrayList<PreviewNotice>();
        adapter = adapterWithPreviewListener(notices);
        game.physicalKeyDown = true;
        completeSwap();
        notices.clear();
        adapter.onLocalBlockDestroyed();
        AutoToolSwapIntent freeze = transport.intents.get(1);
        settle(freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.RUNNING.ordinal(), 4, 5L, true);
        game.inventory = takeoverSource();
        adapter.onTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                10, 64, 20, 42, 7, 6L, 12L, true);
        adapter.onClientTick();
        AutoToolSwapIntent takeover = transport.intents.get(2);
        settle(takeover, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.FROZEN);

        adapter.onClientTick();
        Assert.assertTrue(notices.isEmpty());
        game.inventory = takeoverTarget();
        adapter.onClientTick();
        Assert.assertEquals(1, notices.size());
        PreviewNotice notice = notices.get(0);
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER, notice.action);
        Assert.assertEquals(9L, notice.serverRoundId);
        Assert.assertEquals(takeover.actionSequence(), notice.actionSequence);
        adapter.onClientTick();
        Assert.assertEquals(1, notices.size());
    }

    @Test
    public void waitsTwentyTicksToRetransmitSameRoundThenOrphansAtOneHundredTwenty() {
        adapter.onChainKeyState(true);
        long nonce = transport.rounds.get(0).longValue();
        for (int tick = 0; tick <= 20; tick++) adapter.onClientTick();
        Assert.assertEquals(2, transport.rounds.size());
        Assert.assertEquals(nonce, transport.rounds.get(1).longValue());
        for (int tick = 21; tick < 120; tick++) adapter.onClientTick();
        Assert.assertFalse(adapter.reducerForTests().isOrphaned());
        Assert.assertEquals("deadline 前应只按 20 tick cadence 重发", 6, transport.rounds.size());
        adapter.onClientTick();
        Assert.assertTrue(adapter.reducerForTests().isOrphaned());
        Assert.assertEquals("deadline tick 不得再发送 round", 6, transport.rounds.size());
    }

    @Test
    public void duplicateActionResultDoesNotSettleTwiceAndPhaseIdleClosesRound() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        AutoToolSwapIntent swap = transport.intents.get(0);
        game.inventory = swapped();
        settle(swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        adapter.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, swap.serverRoundId(), swap.actionSequence(),
                swap.action().wireCode(), AutoToolSwapResultCode.APPLIED.wireCode(),
                AutoToolSwapRoundState.SWAPPED.wireCode(), swap.anchorSlot(), swap.candidateSlot(),
                swap.actionSequence() + 1L, 1L, true);
        adapter.onClientTick();
        Assert.assertEquals(1, transport.intents.size());

        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, swap.serverRoundId(), 1L,
                ChainPhase.IDLE.ordinal(), 1, 2L, true);
        Assert.assertEquals(1, transport.intents.size());
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.RESTORE, transport.intents.get(1).action());
    }

    @Test
    public void closeFinishedResetsProtocolAndNextPressUsesNewRoundDespiteDuplicateClose() {
        adapter.onChainKeyState(true);
        long firstNonce = transport.rounds.get(0).longValue();
        acceptRound();
        adapter.onChainKeyState(false);
        AutoToolSwapIntent close = transport.intents.get(0);
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(0L, adapter.reducerForTests().serverRoundId());

        adapter.onChainKeyState(true);
        Assert.assertEquals(2, transport.rounds.size());
        Assert.assertTrue(transport.rounds.get(1).longValue() > firstNonce);
        adapter.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, close.serverRoundId(), close.actionSequence(),
                close.action().wireCode(), AutoToolSwapResultCode.ACCEPTED.wireCode(),
                AutoToolSwapRoundState.FINISHED.wireCode(), close.anchorSlot(), close.candidateSlot(),
                close.actionSequence() + 1L, 1L, true);
        acceptRound(1, 10L);
        Assert.assertEquals(10L, adapter.reducerForTests().serverRoundId());
    }

    @Test
    public void rejectedRoundReleaseAllowsAnotherRoundWithoutClose() {
        adapter.onChainKeyState(true);
        rejectRound(0);
        adapter.onChainKeyState(false);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, adapter.reducerForTests().state());
        Assert.assertTrue(transport.intents.isEmpty());

        adapter.onChainKeyState(true);
        Assert.assertEquals(2, transport.rounds.size());
    }

    @Test
    public void naturalIdleDoesNotSuppressNextCycleChainActive() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true);
        Assert.assertTrue(transport.intents.isEmpty());
        adapter.onClientTick();
        AutoToolSwapIntent close = transport.intents.get(0);
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        adapter.onChainKeyState(false);
        adapter.onChainKeyState(true);

        Assert.assertEquals(2, transport.rounds.size());
    }

    @Test
    public void naturalFinishedCloseRearmsOnNextTickWithFreshNonceOnlyWhilePhysicalLevelStaysDown() {
        game.physicalKeyDown = true;
        adapter.onChainKeyState(true);
        long firstNonce = transport.rounds.get(0).longValue();
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true);

        Assert.assertFalse(adapter.onClientTick());
        AutoToolSwapIntent close = transport.intents.get(0);
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals("S2C callback 内不得发送 RoundStart2", 1, transport.rounds.size());

        Assert.assertTrue(adapter.onClientTick());
        Assert.assertEquals(2, transport.rounds.size());
        Assert.assertTrue(transport.rounds.get(1).longValue() > firstNonce);
        Assert.assertTrue(adapter.reducerForTests().isRoundPending());
        Assert.assertFalse("一次性信号不得重复", adapter.onClientTick());
        rejectRound(1);
        Assert.assertFalse("新 round 被拒绝后不得继续自动创建 nonce", adapter.onClientTick());
        Assert.assertEquals(2, transport.rounds.size());
    }

    @Test
    public void deferredRearmFailsClosedForPhysicalReleaseConfigDisableLifecycleAndRoundStartFailure() {
        finishNaturalRoundWithPhysicalKeyDown();
        game.physicalKeyDown = false;
        Assert.assertFalse(adapter.onClientTick());
        game.physicalKeyDown = true;
        Assert.assertFalse(adapter.onClientTick());
        Assert.assertEquals(1, transport.rounds.size());

        setUpFreshAdapter();
        finishNaturalRoundWithPhysicalKeyDown();
        adapter.onConfigChanged(false, Collections.emptyList());
        Assert.assertFalse(adapter.onClientTick());
        Assert.assertEquals(1, transport.rounds.size());

        setUpFreshAdapter();
        finishNaturalRoundWithPhysicalKeyDown();
        adapter.resetForLifecycle();
        Assert.assertFalse(adapter.onClientTick());
        Assert.assertEquals(1, transport.rounds.size());

        setUpFreshAdapter();
        finishNaturalRoundWithPhysicalKeyDown();
        transport.accept = false;
        Assert.assertFalse(adapter.onClientTick());
        Assert.assertTrue(adapter.reducerForTests().isOrphaned());
        transport.accept = true;
        Assert.assertFalse(adapter.onClientTick());
    }

    @Test
    public void releaseAndQuickRepressDuringNaturalClosingKeepWaitReleaseContract() {
        game.physicalKeyDown = true;
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true);
        adapter.onClientTick();
        AutoToolSwapIntent close = transport.intents.get(0);

        adapter.onChainKeyState(false);
        adapter.onChainKeyState(true);
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.WAIT_RELEASE, adapter.reducerForTests().state());
        Assert.assertFalse(adapter.onClientTick());
        Assert.assertEquals(1, transport.rounds.size());
    }

    @Test
    public void inapplicableCyclesDoNotSendRoundStart() {
        adapter.onConfigChanged(false, Collections.emptyList());
        adapter.onChainKeyState(true);
        Assert.assertTrue(transport.rounds.isEmpty());

        assertNoRoundStart(false, false);
        assertNoRoundStart(true, true);
    }

    @Test
    public void nonCloseSettlementDoesNotResetProtocol() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        AutoToolSwapIntent swap = transport.intents.get(0);
        settle(swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);

        Assert.assertEquals(9L, adapter.reducerForTests().serverRoundId());
    }

    @Test
    public void transportFailureAndLifecycleResetNeverSendRecoveryPackets() {
        transport.accept = false;
        adapter.onChainKeyState(true);
        Assert.assertTrue(adapter.reducerForTests().isOrphaned());

        transport.accept = true;
        adapter.resetForLifecycle();
        int sent = transport.rounds.size() + transport.intents.size();
        adapter.onClientTick();
        Assert.assertEquals(sent, transport.rounds.size() + transport.intents.size());
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, adapter.reducerForTests().state());
    }

    @Test
    public void failedReleaseCapturePreservesLedgerRetriesRestoreThenClosesAndAllowsNewRound() {
        final List<String> diagnostics = new ArrayList<String>();
        adapter = new AutoToolSwapClientAdapter(true, true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientReducer.DiagnosticSink() {
                    @Override public void log(String message) { diagnostics.add(message); }
                });
        completeSwap();
        game.failContextCapture = true;

        adapter.onChainKeyState(false);

        Assert.assertFalse(adapter.reducerForTests().isKeyDown());
        Assert.assertEquals(9L, adapter.reducerForTests().serverRoundId());
        Assert.assertTrue(adapter.reducerForTests().hasSwapExpectation());
        Assert.assertEquals(1, transport.intents.size());
        Assert.assertEquals(1, Collections.frequency(diagnostics,
                AutoToolSwapClientAdapter.RELEASE_CAPTURE_FAILED_MARKER));
        adapter.onChainKeyState(false);
        Assert.assertEquals("同一 release 边沿不得重复输出 marker", 1, Collections.frequency(diagnostics,
                AutoToolSwapClientAdapter.RELEASE_CAPTURE_FAILED_MARKER));

        game.failContextCapture = false;
        adapter.onClientTick();
        AutoToolSwapIntent restore = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        game.inventory = restored();
        settle(restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        adapter.onClientTick();
        AutoToolSwapIntent close = transport.intents.get(2);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.action());
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);

        Assert.assertEquals(0L, adapter.reducerForTests().serverRoundId());
        adapter.onChainKeyState(true);
        Assert.assertEquals(2, transport.rounds.size());
    }

    @Test
    public void failedLightCaptureOnNaturalIdleStillPublishesReleaseAndRisingNullStartsNothing() {
        final List<String> diagnostics = new ArrayList<String>();
        adapter = new AutoToolSwapClientAdapter(true, true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientReducer.DiagnosticSink() {
                    @Override public void log(String message) { diagnostics.add(message); }
                });
        game.inventory = noCandidate();
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true);
        game.failLightCapture = true;

        adapter.onChainKeyState(false);

        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(0).action());
        Assert.assertEquals(1, Collections.frequency(diagnostics,
                AutoToolSwapClientAdapter.RELEASE_CAPTURE_FAILED_MARKER));
        AutoToolSwapIntent close = transport.intents.get(0);
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        adapter.onChainKeyState(true);
        Assert.assertEquals("上升沿采样失败不得建立新 round", 1, transport.rounds.size());
    }

    @Test
    public void preEdgeDestroyLatchesFreezeAndDrainsAfterRoundAcceptance() {
        game.physicalKeyDown = true;
        adapter.onLocalBlockDestroyed();
        adapter.onChainKeyState(true);
        acceptRound();
        Assert.assertTrue(transport.intents.isEmpty());
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.FREEZE, transport.intents.get(0).action());
    }

    @Test
    public void frozenSettlementThenActivePhasesKeepTheServerToolWithoutMoreIntents() {
        game.physicalKeyDown = true;
        completeSwap();
        adapter.onLocalBlockDestroyed();
        Assert.assertEquals(2, transport.intents.size());
        AutoToolSwapIntent freeze = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.action());
        settle(freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);

        ChainPhase[] phases = {ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING,
                ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING};
        for (int index = 0; index < phases.length; index++) {
            adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, index + 1L,
                    phases[index].ordinal(), 1, index + 2L, true);
            adapter.onClientTick();
            Assert.assertEquals("活跃 phase 不得发送第二次 FREEZE、RESTORE 或 CLOSE", 2,
                    transport.intents.size());
        }
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, adapter.reducerForTests().state());
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, adapter.reducerForTests().serverRoundState());
        Assert.assertTrue(adapter.reducerForTests().hasSwapExpectation());
    }

    @Test
    public void closingUnavailableFreezeConvergesToOneCloseWithoutOrphaning() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 1L, true);
        adapter.onChainKeyState(false);

        adapter.onClientTick();
        Assert.assertEquals(1, transport.intents.size());
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(0).action());
        Assert.assertFalse(adapter.reducerForTests().isOrphaned());
        adapter.onClientTick();
        Assert.assertEquals(1, transport.intents.size());
    }

    @Test
    public void rejectedFreezeInClosingRestoresThenClosesWithoutOrphaning() {
        completeSwap();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 2L, true);
        adapter.onClientTick();
        AutoToolSwapIntent freeze = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.action());

        settle(freeze, AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.CLOSING);
        adapter.onClientTick();
        AutoToolSwapIntent restore = transport.intents.get(2);
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());

        game.inventory = restored();
        settle(restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(3).action());
        Assert.assertFalse(adapter.reducerForTests().isOrphaned());
    }

    @Test
    public void orphanedProtocolAndIntentTransportFailureStillFailClosed() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 1L, true);
        game = new FakeGame();
        transport = new RecordingTransport();
        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport);
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 1L, true);
        transport.accept = false;
        adapter.onClientTick();
        Assert.assertTrue(adapter.reducerForTests().isOrphaned());
    }

    @Test
    public void quickRepressWaitsForTheNextCompleteReleaseAfterCloseSettlement() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onChainKeyState(false);
        AutoToolSwapIntent close = transport.intents.get(0);
        adapter.onChainKeyState(true);
        Assert.assertEquals(1, transport.rounds.size());

        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.WAIT_RELEASE, adapter.reducerForTests().state());
        adapter.onChainKeyState(false);
        adapter.onChainKeyState(true);
        Assert.assertEquals(2, transport.rounds.size());
    }

    @Test
    public void actionResultDefersRestoreRetryUntilNextClientTick() {
        completeSwap();
        adapter.onChainKeyState(false);
        AutoToolSwapIntent firstRestore = transport.intents.get(1);
        settle(firstRestore, AutoToolSwapResultCode.REJECTED, AutoToolSwapRoundState.CLOSING);
        Assert.assertEquals(2, transport.intents.size());
        adapter.onClientTick();
        Assert.assertEquals(3, transport.intents.size());
        Assert.assertEquals(AutoToolSwapAction.RESTORE, transport.intents.get(2).action());
    }

    @Test
    public void guiAndUnsafeCursorKeepRestoreObligationWithoutSendingUntilSafe() {
        completeSwap();
        game.guiOpen = true;
        adapter.onChainKeyState(false);
        Assert.assertEquals(1, transport.intents.size());
        game.guiOpen = false;
        adapter.onClientTick();
        AutoToolSwapIntent guiRestore = transport.intents.get(1);
        game.inventory = restored();
        settle(guiRestore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        adapter.onClientTick();
        Assert.assertFalse(adapter.reducerForTests().hasSwapExpectation());

        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport);
        game.inventory = restored();
        completeSwap();
        int intentsBeforeUnsafeRestore = transport.intents.size();
        game.inventoryTransactionSafe = false;
        adapter.onChainKeyState(false);
        Assert.assertEquals(intentsBeforeUnsafeRestore, transport.intents.size());
        game.inventoryTransactionSafe = true;
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.RESTORE,
                transport.intents.get(intentsBeforeUnsafeRestore).action());
    }

    @Test
    public void creativeModeNeverSendsRestoreIntentAndResumesAfterLeavingCreative() {
        completeSwap();
        game.creative = true;
        adapter.onChainKeyState(false);
        Assert.assertEquals(1, transport.intents.size());
        game.creative = false;
        adapter.onClientTick();
        AutoToolSwapIntent restore = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        game.inventory = restored();
        settle(restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        adapter.onClientTick();
        Assert.assertFalse(adapter.reducerForTests().hasSwapExpectation());
    }

    @Test
    public void incompatibleRestoreSendsAbandonAndTransportFailureStillOrphans() {
        completeSwap();
        game.inventory = third();
        adapter.onChainKeyState(false);
        Assert.assertEquals(1, transport.intents.size());
        adapter.onClientTick();
        AutoToolSwapIntent abandon = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.ABANDON, abandon.action());
        settle(abandon, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, adapter.reducerForTests().state());
        Assert.assertFalse(adapter.reducerForTests().hasSwapExpectation());

        setUpFreshAdapter();
        completeSwap();
        game.inventory = third();
        adapter.onChainKeyState(false);
        transport.accept = false;
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.ABANDON,
                transport.intents.get(transport.intents.size() - 1).action());
        Assert.assertTrue(adapter.reducerForTests().isOrphaned());
    }

    @Test
    public void emptyOriginalAnchorWithOccupiedCandidateSendsRestoreAndVerifiesRealExchange() {
        game.inventory = emptyRestored();
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        AutoToolSwapIntent swap = transport.intents.get(0);
        game.inventory = emptySwappedWithOccupant();
        settle(swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        adapter.onClientTick();

        adapter.onChainKeyState(false);
        AutoToolSwapIntent restore = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        game.inventory = emptyRestoreResult();
        settle(restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        adapter.onClientTick();

        Assert.assertFalse(adapter.reducerForTests().hasSwapExpectation());
        Assert.assertFalse(adapter.reducerForTests().isOrphaned());
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(2).action());
    }

    @Test
    public void takeoverRequestWaitsForNextTickUsesServerTargetAndRollsThreeSlotExpectation() {
        game.physicalKeyDown = true;
        completeSwap();
        adapter.onLocalBlockDestroyed();
        AutoToolSwapIntent freeze = transport.intents.get(1);
        settle(freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.RUNNING.ordinal(), 4, 5L, true);
        game.inventory = takeoverSource();

        adapter.onTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 4,
                10, 64, 20, 42, 7, 6L, 12L, true);
        Assert.assertEquals("S2C callback 不得发送 C2S", 2, transport.intents.size());

        adapter.onClientTick();
        AutoToolSwapIntent takeover = transport.intents.get(2);
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER, takeover.action());
        Assert.assertEquals(7, takeover.candidateSlot());
        Assert.assertEquals(42, game.lastTargetBlockId);
        Assert.assertEquals(7, game.lastTargetMetadata);

        game.inventory = takeoverTarget();
        settle(takeover, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.FROZEN);
        adapter.onClientTick();
        Assert.assertFalse(adapter.reducerForTests().isInventorySyncPending());
        Assert.assertEquals(7, adapter.reducerForTests().protectedCandidateSlot());
    }

    @Test
    public void disabledTakeoverDeclinesOnNextTickWithoutInventoryTransaction() {
        game.physicalKeyDown = true;
        completeSwap();
        adapter.onLocalBlockDestroyed();
        AutoToolSwapIntent freeze = transport.intents.get(1);
        settle(freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.RUNNING.ordinal(), 2, 2L, true);
        adapter.onConfigChanged(true, false, Collections.emptyList());
        adapter.onTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 3L, 2,
                1, 60, 1, 1, 0, 3L, 9L, true);

        Assert.assertEquals(2, transport.intents.size());
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER, transport.intents.get(2).action());
    }

    @Test
    public void preparingTargetChangeRestoresBeforeLatestTargetSwap() {
        completeSwap();
        game.targetIdentity = ToolSwapTargetIdentity.present(2, 0);

        adapter.onClientTick();
        AutoToolSwapIntent restore = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        game.targetIdentity = ToolSwapTargetIdentity.present(3, 1);
        game.inventory = restored();
        settle(restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.OPEN);
        adapter.onClientTick();
        Assert.assertEquals("RESTORE 可见性确认 tick 不得直接发送第二个 SWAP", 2,
                transport.intents.size());

        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapAction.SWAP, transport.intents.get(2).action());
    }

    @Test
    public void stableAbsentUsesNoRepeatedFullCaptureAndTargetReturnRestoresFullCadence() {
        game.inventory = noCandidate();
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        Assert.assertEquals("按键 FULL 与首次匹配 FULL", 2, game.fullCaptureCount);

        game.targetIdentity = ToolSwapTargetIdentity.ABSENT;
        for (int tick = 1; tick < 25; tick++) adapter.onClientTick();
        Assert.assertEquals("连续确认 ABSENT 并越过多个水位后不得再遍历 36 槽",
                2, game.fullCaptureCount);
        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                game.capturePlans.get(game.capturePlans.size() - 1));

        game.targetIdentity = ToolSwapTargetIdentity.present(2, 1);
        adapter.onClientTick();
        Assert.assertEquals("目标恢复只新增一次即时 FULL", 3, game.fullCaptureCount);
        Assert.assertEquals(ToolSwapCapturePlan.FULL,
                game.capturePlans.get(game.capturePlans.size() - 1));

        for (int tick = 26; tick < 35; tick++) adapter.onClientTick();
        Assert.assertEquals("稳定有效目标到水位前不得额外 FULL", 3, game.fullCaptureCount);
        adapter.onClientTick();
        Assert.assertEquals("稳定有效目标保留十 tick 周期 FULL", 4, game.fullCaptureCount);
        Assert.assertTrue("无候选时采样回归不应依赖网络 effect", transport.intents.isEmpty());
    }

    @Test
    public void releaseConfigDisableAndFreezeStopOrdinaryFullBeforeWatermarkEvaluation() {
        game.inventory = noCandidate();
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        Assert.assertEquals(2, game.fullCaptureCount);

        adapter.onChainKeyState(false);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(0).action());
        game.inventory = restored();
        game.targetIdentity = ToolSwapTargetIdentity.present(2, 1);
        adapter.onClientTick();
        game.targetIdentity = ToolSwapTargetIdentity.present(1, 0);
        while (adapter.reducerForTests().clientTick() <= 10L) adapter.onClientTick();
        Assert.assertEquals("松键后目标变化与到期水位均不得再遍历 36 槽", 2, game.fullCaptureCount);
        Assert.assertFalse("松键后已捕获上下文也不得创建新 SWAP ledger",
                adapter.reducerForTests().hasSwapExpectation());
        Assert.assertEquals("松键后只能保留 CLOSE 控制动作", 1, transport.intents.size());

        setUpFreshAdapter();
        game.inventory = noCandidate();
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        adapter.onConfigChanged(false, Collections.emptyList());
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(0).action());
        game.inventory = restored();
        game.targetIdentity = ToolSwapTargetIdentity.present(2, 1);
        adapter.onClientTick();
        game.targetIdentity = ToolSwapTargetIdentity.present(1, 0);
        while (adapter.reducerForTests().clientTick() <= 10L) adapter.onClientTick();
        Assert.assertEquals("配置关闭后目标变化与到期水位均不得再遍历 36 槽", 2, game.fullCaptureCount);
        Assert.assertFalse("配置关闭后不得创建新 SWAP ledger",
                adapter.reducerForTests().hasSwapExpectation());
        Assert.assertEquals("配置关闭后只能保留 CLOSE 控制动作", 1, transport.intents.size());

        setUpFreshAdapter();
        game.inventory = noCandidate();
        game.physicalKeyDown = true;
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        adapter.onLocalBlockDestroyed();
        Assert.assertEquals(AutoToolSwapAction.FREEZE, transport.intents.get(0).action());
        game.inventory = restored();
        game.targetIdentity = ToolSwapTargetIdentity.present(2, 1);
        while (adapter.reducerForTests().clientTick() <= 10L) adapter.onClientTick();
        Assert.assertEquals("FREEZE 请求后不得再遍历 36 槽", 2, game.fullCaptureCount);
        Assert.assertFalse("FREEZE 请求后不得创建新 SWAP ledger",
                adapter.reducerForTests().hasSwapExpectation());
        Assert.assertEquals("FREEZE 请求后只能保留 FREEZE 控制动作", 1, transport.intents.size());
    }

    private void acceptRound() {
        acceptRound(0, 9L);
    }

    private void acceptRound(int index, long roundId) {
        adapter.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, transport.rounds.get(index).longValue(), roundId,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(), 1L, 0L, true);
    }

    private void rejectRound(int index) {
        adapter.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, transport.rounds.get(index).longValue(), 0L,
                AutoToolSwapResultCode.REJECTED.wireCode(), AutoToolSwapRoundState.PENDING_KEY.wireCode(), 1L, 0L,
                true);
    }

    private void completeSwap() {
        adapter.onChainKeyState(true);
        acceptRound(transport.rounds.size() - 1, 9L);
        adapter.onClientTick();
        AutoToolSwapIntent swap = transport.intents.get(transport.intents.size() - 1);
        Assert.assertEquals(AutoToolSwapAction.SWAP, swap.action());
        game.inventory = swapped();
        settle(swap, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.SWAPPED);
        adapter.onClientTick();
        Assert.assertTrue(adapter.reducerForTests().hasSwapExpectation());
    }

    private void finishNaturalRoundWithPhysicalKeyDown() {
        game.physicalKeyDown = true;
        adapter.onChainKeyState(true);
        acceptRound(transport.rounds.size() - 1, 9L);
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 1, 1L, true);
        adapter.onClientTick();
        AutoToolSwapIntent close = transport.intents.get(transport.intents.size() - 1);
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
    }

    private void setUpFreshAdapter() {
        game = new FakeGame();
        transport = new RecordingTransport();
        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport);
    }

    private AutoToolSwapClientAdapter adapterWithPreviewListener(final List<PreviewNotice> notices) {
        return new AutoToolSwapClientAdapter(true, true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientAdapter.PreviewInvalidationListener() {
                    @Override
                    public void onPreviewInvalidated(long cycleGeneration, long serverRoundId,
                            long actionSequence, AutoToolSwapAction action) {
                        notices.add(new PreviewNotice(cycleGeneration, serverRoundId, actionSequence, action));
                    }
                });
    }

    private void assertNoRoundStart(boolean breakCapable, boolean creative) {
        FakeGame invalidGame = new FakeGame();
        invalidGame.breakCapable = breakCapable;
        invalidGame.creative = creative;
        RecordingTransport invalidTransport = new RecordingTransport();
        AutoToolSwapClientAdapter invalidAdapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(),
                invalidGame, invalidTransport);
        invalidAdapter.onChainKeyState(true);
        Assert.assertTrue(invalidTransport.rounds.isEmpty());
    }

    private void settle(AutoToolSwapIntent intent, AutoToolSwapResultCode result, AutoToolSwapRoundState state) {
        long nextActionSequence = intent.usesTakeoverRequestId()
                ? adapter.reducerForTests().nextActionSequence() : intent.actionSequence() + 1L;
        adapter.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, intent.serverRoundId(), intent.actionSequence(),
                intent.action().wireCode(), result.wireCode(), state.wireCode(), intent.anchorSlot(),
                intent.candidateSlot(), nextActionSequence, 1L, true);
    }

    private static ToolSwapInventorySnapshot restored() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(0, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot swapped() {
        return inventory(new SlotSnapshot(0, "pick", "used"), new SlotSnapshot(5, "hand", "old"),
                tool(0, "pick", true), tool(5, "hand", false));
    }

    private static ToolSwapInventorySnapshot noCandidate() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "empty", ""),
                tool(0, "hand", false));
    }

    private static ToolSwapInventorySnapshot third() {
        return inventory(new SlotSnapshot(0, "pick", "energy=20"),
                new SlotSnapshot(5, "foreign", "occupied"), tool(0, "pick", true));
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

    private static ToolSwapInventorySnapshot takeoverSource() {
        return inventory(new SlotSnapshot(0, "pick", "used"),
                new SlotSnapshot(5, "hand", "old"), new SlotSnapshot(7, "drill", "fresh"),
                tool(0, "pick", false), tool(7, "drill", true));
    }

    private static ToolSwapInventorySnapshot takeoverTarget() {
        return inventory(new SlotSnapshot(0, "drill", "fresh"),
                new SlotSnapshot(5, "pick", "used"), new SlotSnapshot(7, "hand", "old"),
                tool(0, "drill", true));
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

    private static final class RecordingTransport implements AutoToolSwapClientTransport {
        private final List<Long> rounds = new ArrayList<Long>();
        private final List<AutoToolSwapIntent> intents = new ArrayList<AutoToolSwapIntent>();
        private boolean accept = true;
        @Override public boolean sendRoundStart(long clientNonce) { rounds.add(Long.valueOf(clientNonce)); return accept; }
        @Override public boolean sendIntent(AutoToolSwapIntent intent) { intents.add(intent); return accept; }
    }

    private static final class PreviewNotice {
        private final long cycleGeneration;
        private final long serverRoundId;
        private final long actionSequence;
        private final AutoToolSwapAction action;

        private PreviewNotice(long cycleGeneration, long serverRoundId,
                long actionSequence, AutoToolSwapAction action) {
            this.cycleGeneration = cycleGeneration;
            this.serverRoundId = serverRoundId;
            this.actionSequence = actionSequence;
            this.action = action;
        }
    }

    private static final class FakeGame implements AutoToolSwapClientAdapter.GameFacade {
        private ToolSwapInventorySnapshot inventory = restored();
        private boolean physicalKeyDown;
        private boolean breakCapable = true;
        private boolean creative;
        private boolean guiOpen;
        private boolean inventoryTransactionSafe = true;
        private boolean failLightCapture;
        private boolean failContextCapture;
        private int lastTargetBlockId;
        private int lastTargetMetadata;
        private ToolSwapTargetIdentity targetIdentity = ToolSwapTargetIdentity.present(1, 0);
        private final List<ToolSwapCapturePlan> capturePlans = new ArrayList<ToolSwapCapturePlan>();
        private int fullCaptureCount;
        @Override public ToolSwapLightContext captureLightContext(long tick, boolean active) {
            if (failLightCapture) return null;
            return new ToolSwapLightContext(tick, breakCapable, creative, guiOpen, active, 0, targetIdentity);
        }
        @Override public ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
                int anchor, int candidate, int targetBlockId, int targetBlockMetadata) {
            if (failContextCapture) return null;
            capturePlans.add(plan);
            if (plan == ToolSwapCapturePlan.FULL || plan == ToolSwapCapturePlan.FULL_TARGET) {
                fullCaptureCount++;
            }
            lastTargetBlockId = targetBlockId;
            lastTargetMetadata = targetBlockMetadata;
            ToolSwapInventorySnapshot captured = inventory;
            if (plan == ToolSwapCapturePlan.PROTECTED) {
                ArrayList<SlotSnapshot> slots = new ArrayList<SlotSnapshot>();
                if (inventory.slot(anchor) != null) slots.add(inventory.slot(anchor));
                if (inventory.slot(candidate) != null) slots.add(inventory.slot(candidate));
                captured = ToolSwapInventorySnapshot.protectedSlots(slots);
            } else if (plan == ToolSwapCapturePlan.NONE) captured = ToolSwapInventorySnapshot.none();
            return new ToolSwapContext(light, inventoryTransactionSafe && !light.guiOpen, light.guiOpen, 0, captured);
        }
        @Override public boolean isChainKeyPhysicallyDown() { return physicalKeyDown; }
    }
}
