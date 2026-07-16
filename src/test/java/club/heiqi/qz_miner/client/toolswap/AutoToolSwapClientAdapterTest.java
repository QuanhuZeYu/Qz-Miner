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
import club.heiqi.qz_miner.client.toolswap.protocol.AutoToolSwapClientProtocolPhase;
import club.heiqi.qz_miner.client.toolswap.protocol.AutoToolSwapClientProtocolState;
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
        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientProtocolState());
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
        Assert.assertEquals(ToolSwapTransactionState.INVENTORY_SYNC_VERIFY,
                adapter.controllerForTests().transactionState());
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.IDLE, adapter.controllerForTests().transactionState());
    }

    @Test
    public void waitsTwentyTicksToRetransmitSameRoundThenOrphansAtForty() {
        adapter.onChainKeyState(true);
        long nonce = transport.rounds.get(0).longValue();
        for (int tick = 0; tick <= 20; tick++) adapter.onClientTick();
        Assert.assertEquals(2, transport.rounds.size());
        Assert.assertEquals(nonce, transport.rounds.get(1).longValue());
        for (int tick = 21; tick <= 40; tick++) adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED,
                adapter.controllerForTests().transactionState());
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
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.IDLE, adapter.protocolForTests().snapshot().phase());

        adapter.onChainKeyState(true);
        Assert.assertEquals(2, transport.rounds.size());
        Assert.assertTrue(transport.rounds.get(1).longValue() > firstNonce);
        adapter.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, close.serverRoundId(), close.actionSequence(),
                close.action().wireCode(), AutoToolSwapResultCode.ACCEPTED.wireCode(),
                AutoToolSwapRoundState.FINISHED.wireCode(), close.anchorSlot(), close.candidateSlot(),
                close.actionSequence() + 1L, 1L, true);
        acceptRound(1, 10L);
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.OPEN, adapter.protocolForTests().snapshot().phase());
    }

    @Test
    public void rejectedRoundReleaseAllowsAnotherRoundWithoutClose() {
        adapter.onChainKeyState(true);
        rejectRound(0);
        adapter.onChainKeyState(false);
        Assert.assertEquals(AutoToolSwapState.IDLE, adapter.controllerForTests().state());
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
        Assert.assertEquals(AutoToolSwapClientProtocolPhase.WAIT_ROUND,
                adapter.protocolForTests().snapshot().phase());
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
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED,
                adapter.controllerForTests().transactionState());
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
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, adapter.controllerForTests().state());
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

        Assert.assertEquals(AutoToolSwapClientProtocolPhase.OPEN, adapter.protocolForTests().snapshot().phase());
    }

    @Test
    public void transportFailureAndLifecycleResetNeverSendRecoveryPackets() {
        transport.accept = false;
        adapter.onChainKeyState(true);
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED,
                adapter.controllerForTests().transactionState());

        transport.accept = true;
        adapter.resetForLifecycle();
        int sent = transport.rounds.size() + transport.intents.size();
        adapter.onClientTick();
        Assert.assertEquals(sent, transport.rounds.size() + transport.intents.size());
        Assert.assertEquals(AutoToolSwapState.IDLE, adapter.controllerForTests().state());
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
    public void closingUnavailableFreezeConvergesToOneCloseWithoutOrphaning() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 1L, true);
        adapter.protocolForTests().markClosing();

        adapter.onClientTick();
        Assert.assertEquals(1, transport.intents.size());
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(0).action());
        Assert.assertNotEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED,
                adapter.controllerForTests().transactionState());
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
        Assert.assertNotEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED,
                adapter.controllerForTests().transactionState());
    }

    @Test
    public void orphanedProtocolAndIntentTransportFailureStillFailClosed() {
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 1L, true);
        adapter.protocolForTests().abandonCurrentRound();
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED,
                adapter.controllerForTests().transactionState());

        game = new FakeGame();
        transport = new RecordingTransport();
        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientProtocolState());
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.PLANNING.ordinal(), 1, 1L, true);
        transport.accept = false;
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED,
                adapter.controllerForTests().transactionState());
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
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, adapter.controllerForTests().state());
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
        Assert.assertFalse(adapter.controllerForTests().hasLedger());

        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientProtocolState());
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
        Assert.assertFalse(adapter.controllerForTests().hasLedger());
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
        Assert.assertTrue(adapter.controllerForTests().hasLedger());
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
        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientProtocolState());
    }

    private void assertNoRoundStart(boolean breakCapable, boolean creative) {
        FakeGame invalidGame = new FakeGame();
        invalidGame.breakCapable = breakCapable;
        invalidGame.creative = creative;
        RecordingTransport invalidTransport = new RecordingTransport();
        AutoToolSwapClientAdapter invalidAdapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(),
                invalidGame, invalidTransport, new AutoToolSwapClientProtocolState());
        invalidAdapter.onChainKeyState(true);
        Assert.assertTrue(invalidTransport.rounds.isEmpty());
    }

    private void settle(AutoToolSwapIntent intent, AutoToolSwapResultCode result, AutoToolSwapRoundState state) {
        adapter.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION, intent.serverRoundId(), intent.actionSequence(),
                intent.action().wireCode(), result.wireCode(), state.wireCode(), intent.anchorSlot(),
                intent.candidateSlot(), intent.actionSequence() + 1L, 1L, true);
    }

    private static ToolSwapInventorySnapshot restored() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(0, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot swapped() {
        return inventory(new SlotSnapshot(0, "pick", "used"), new SlotSnapshot(5, "hand", "old"),
                tool(0, "pick", true), tool(5, "hand", false));
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

    private static final class FakeGame implements AutoToolSwapClientAdapter.GameFacade {
        private ToolSwapInventorySnapshot inventory = restored();
        private boolean physicalKeyDown;
        private boolean breakCapable = true;
        private boolean creative;
        private boolean guiOpen;
        private boolean inventoryTransactionSafe = true;
        @Override public ToolSwapLightContext captureLightContext(long tick, boolean active) {
            return new ToolSwapLightContext(tick, breakCapable, creative, guiOpen, active, 0);
        }
        @Override public ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
                int anchor, int candidate) {
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
