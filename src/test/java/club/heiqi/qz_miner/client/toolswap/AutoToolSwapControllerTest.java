package club.heiqi.qz_miner.client.toolswap;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 服务端 round、单 ledger 与库存双门的核心合同。 */
public class AutoToolSwapControllerTest {

    @Test
    public void risingEdgeBeginsRoundBeforeAnySwapAndRejectedRoundHasNoLedgerAction() {
        AutoToolSwapController controller = controller();
        controller.onKeyState(true, context(0, restored()), false);
        Assert.assertEquals(ToolSwapTransactionState.ROUND_PENDING, controller.transactionState());
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_ROUND, only(controller).type);
        Assert.assertTrue(controller.drainCommands().isEmpty());

        controller.onRoundRejected();
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, controller.state());
        Assert.assertFalse(controller.hasLedger());
    }

    @Test
    public void releaseAfterRejectedRoundReturnsIdleWithoutCloseAndCanBeginAnotherRound() {
        AutoToolSwapController controller = controller();
        controller.onKeyState(true, context(0, restored()), false);
        only(controller);
        controller.onRoundRejected();

        controller.onKeyState(false, context(1, restored()), false);
        Assert.assertEquals(AutoToolSwapState.IDLE, controller.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        Assert.assertTrue(controller.drainCommands().isEmpty());

        controller.onKeyState(true, context(2, restored()), false);
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_ROUND, only(controller).type);
    }

    @Test
    public void inapplicableCycleWaitsForReleaseWithoutOpeningRound() {
        assertInapplicableCycleHasNoRound(new ToolSwapContext(0, true, false, false, true, false, 0, restored()));
        assertInapplicableCycleHasNoRound(new ToolSwapContext(0, false, false, false, true, true, 0, restored()));
        assertInapplicableCycleHasNoRound(new ToolSwapContext(0, true, true, false, true, true, 0, restored()));
    }

    @Test
    public void appliedResultAndTargetInventoryAreIndependentGates() {
        AutoToolSwapController controller = acceptedSwap();
        ToolSwapCommand swap = only(controller);
        Assert.assertTrue(controller.onActionPreflight(swap, protectedSlots(restored())));
        controller.onActionStarted(AutoToolSwapAction.SWAP);
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 0);
        Assert.assertEquals(ToolSwapTransactionState.INVENTORY_SYNC_VERIFY, controller.transactionState());
        controller.onInventoryObserved(protectedSlots(restored()), 39);
        Assert.assertEquals(ToolSwapTransactionState.INVENTORY_SYNC_VERIFY, controller.transactionState());
        controller.onInventoryObserved(protectedSlots(swapped()), 40);
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        Assert.assertTrue(controller.hasLedger());
    }

    @Test
    public void inventoryFirstThenAppliedReadsCurrentProtectedSlots() {
        AutoToolSwapController controller = acceptedSwap();
        ToolSwapCommand swap = only(controller);
        Assert.assertTrue(controller.onActionPreflight(swap, protectedSlots(restored())));
        controller.onActionStarted(AutoToolSwapAction.SWAP);
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 5);
        controller.onInventoryObserved(protectedSlots(swapped()), 5);
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
    }

    @Test
    public void thirdLayoutAndOldLayoutTimeoutOrphanTheProtocol() {
        AutoToolSwapController third = swapWaitingForInventory();
        third.onInventoryObserved(protectedSlots(third()), 1);
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED, third.transactionState());

        AutoToolSwapController timeout = swapWaitingForInventory();
        timeout.onInventoryObserved(protectedSlots(restored()), 40);
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED, timeout.transactionState());
    }

    @Test
    public void untrustedInventoryWaitsUntilTimeoutWhileTargetLayoutWinsAtTimeout() {
        AutoToolSwapController waiting = swapWaitingForInventory();
        waiting.onInventoryObserved(ToolSwapInventorySnapshot.untrusted(), 39);
        Assert.assertEquals(ToolSwapTransactionState.INVENTORY_SYNC_VERIFY, waiting.transactionState());
        waiting.onInventoryObserved(ToolSwapInventorySnapshot.untrusted(), 40);
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED, waiting.transactionState());

        AutoToolSwapController target = swapWaitingForInventory();
        target.onInventoryObserved(protectedSlots(swapped()), 40);
        Assert.assertEquals(ToolSwapTransactionState.IDLE, target.transactionState());
    }

    @Test
    public void releaseRestoresThenClosesButGuiRestoreKeepsRoundOpen() {
        AutoToolSwapController closing = completedSwap();
        closing.onKeyState(false, context(2, swapped()), false);
        ToolSwapCommand closeRestore = only(closing);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, closeRestore.type);
        settleRestore(closing, closeRestore, 2);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_CLOSE, only(closing).type);

        AutoToolSwapController gui = completedSwap();
        gui.onTick(context(2, true, swapped()));
        ToolSwapCommand guiRestore = only(gui);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, guiRestore.type);
        settleRestore(gui, guiRestore, 2);
        Assert.assertTrue(gui.drainCommands().isEmpty());
        Assert.assertEquals(AutoToolSwapState.PREPARING, gui.state());
    }

    @Test
    public void preFrozenAndNoCandidateCanCloseWithCanonicalControls() {
        AutoToolSwapController frozen = controller();
        frozen.onKeyState(true, context(0, noCandidate()), true);
        only(frozen);
        frozen.onRoundAccepted();
        Assert.assertEquals(ToolSwapCommand.Type.SEND_FREEZE, only(frozen).type);

        AutoToolSwapController noCandidate = controller();
        noCandidate.onKeyState(true, context(0, noCandidate()), false);
        only(noCandidate);
        noCandidate.onRoundAccepted();
        noCandidate.onKeyState(false, context(1, noCandidate()), false);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_CLOSE, only(noCandidate).type);
    }

    @Test
    public void releaseReplacesQueuedFreezeWithCurrentCloseObligation() {
        AutoToolSwapController controller = controller();
        controller.onKeyState(true, context(0, restored()), true);
        only(controller);
        controller.onRoundAccepted();
        controller.onKeyState(false, context(1, restored()), false);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_CLOSE, only(controller).type);
    }

    @Test
    public void releaseReplacesQueuedFreezeWithRestoreBeforeCloseWhenLedgerExists() {
        AutoToolSwapController controller = completedSwap();
        controller.onDedicatedPhase(club.heiqi.qz_miner.chain.statemachine.ChainPhase.PLANNING);
        controller.onKeyState(false, context(2, swapped()), false);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, only(controller).type);
    }

    @Test
    public void guiCancelsQueuedSwapAndRetractsUnsentLedger() {
        AutoToolSwapController controller = acceptedSwap();
        controller.onTick(context(1, true, restored()));
        Assert.assertFalse(controller.hasLedger());
        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
        Assert.assertTrue(controller.drainCommands().isEmpty());
    }

    @Test
    public void guiCancelledSwapDoesNotRestoreAfterTheNextAppliedSwap() {
        AutoToolSwapController controller = acceptedSwap();
        controller.onTick(context(1, true, restored()));
        Assert.assertTrue(controller.drainCommands().isEmpty());

        controller.onTick(context(20, restored()));
        ToolSwapCommand swap = only(controller);
        Assert.assertTrue(controller.onActionPreflight(swap, protectedSlots(restored())));
        controller.onActionStarted(AutoToolSwapAction.SWAP);
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 20);
        controller.onInventoryObserved(protectedSlots(swapped()), 20);

        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
        Assert.assertTrue(controller.hasLedger());
        Assert.assertTrue(controller.drainCommands().isEmpty());
    }

    @Test
    public void reanchorCancelledSwapUsesNewAnchorWithoutRestoringAfterApply() {
        AutoToolSwapController controller = acceptedSwap();
        controller.onTick(context(1, 1, reanchoredRestored()));
        Assert.assertTrue(controller.drainCommands().isEmpty());

        controller.onTick(context(20, 1, reanchoredRestored()));
        ToolSwapCommand swap = only(controller);
        Assert.assertEquals(1, swap.anchorSlot);
        Assert.assertTrue(controller.onActionPreflight(swap, protectedSlots(reanchoredRestored(), 1, 5)));
        controller.onActionStarted(AutoToolSwapAction.SWAP);
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 20);
        controller.onInventoryObserved(protectedSlots(reanchoredSwapped(), 1, 5), 20);

        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
        Assert.assertTrue(controller.hasLedger());
        Assert.assertTrue(controller.drainCommands().isEmpty());
    }

    @Test
    public void activePhaseReplacesQueuedSwapWithFreezeButKeepsStartedSwapInFlight() {
        AutoToolSwapController queued = acceptedSwap();
        queued.onDedicatedPhase(club.heiqi.qz_miner.chain.statemachine.ChainPhase.PLANNING);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_FREEZE, only(queued).type);
        Assert.assertFalse(queued.hasLedger());

        AutoToolSwapController started = acceptedSwap();
        ToolSwapCommand swap = only(started);
        Assert.assertTrue(started.onActionPreflight(swap, protectedSlots(restored())));
        started.onActionStarted(AutoToolSwapAction.SWAP);
        started.onDedicatedPhase(club.heiqi.qz_miner.chain.statemachine.ChainPhase.PLANNING);
        Assert.assertEquals(ToolSwapTransactionState.ACTION_RESULT_PENDING, started.transactionState());
        Assert.assertTrue(started.drainCommands().isEmpty());
        started.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 1);
        started.onInventoryObserved(protectedSlots(swapped()), 1);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_FREEZE, only(started).type);
    }

    @Test
    public void swapRejectionUsesServerRoundStateToRetryFreezeOrClose() {
        AutoToolSwapController open = startedSwap();
        open.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.OPEN, 0);
        open.onTick(context(19, restored()));
        Assert.assertTrue(open.drainCommands().isEmpty());
        open.onTick(context(20, restored()));
        Assert.assertEquals(ToolSwapCommand.Type.SEND_SWAP, only(open).type);

        AutoToolSwapController frozen = startedSwap();
        frozen.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.FROZEN, 0);
        frozen.onTick(context(10, restored()));
        Assert.assertEquals(AutoToolSwapState.FROZEN, frozen.state());
        Assert.assertTrue(frozen.drainCommands().isEmpty());

        AutoToolSwapController closing = startedSwap();
        closing.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.CLOSING, 0);
        Assert.assertEquals(ToolSwapCommand.Type.SEND_CLOSE, only(closing).type);
        Assert.assertFalse(closing.hasLedger());
    }

    @Test
    public void rejectedRestoreRetainsLedgerAndPreflightFailureRetractsSwap() {
        AutoToolSwapController restore = completedSwap();
        restore.onKeyState(false, context(2, swapped()), false);
        ToolSwapCommand command = only(restore);
        Assert.assertTrue(restore.onActionPreflight(command, protectedSlots(swapped())));
        restore.onActionStarted(AutoToolSwapAction.RESTORE);
        restore.onActionSettled(AutoToolSwapAction.RESTORE, AutoToolSwapResultCode.REJECTED,
                AutoToolSwapRoundState.CLOSING, 2);
        restore.onTick(context(3, swapped()));
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, only(restore).type);

        AutoToolSwapController swap = acceptedSwap();
        ToolSwapCommand swapCommand = only(swap);
        Assert.assertFalse(swap.onActionPreflight(swapCommand, protectedSlots(noCandidate())));
        Assert.assertFalse(swap.hasLedger());
        Assert.assertEquals(AutoToolSwapState.PREPARING, swap.state());
    }

    @Test
    public void unsafeRestorePreflightClearsOnlyTheQueuedCommandUntilSafeAgain() {
        AutoToolSwapController gui = completedSwap();
        gui.onKeyState(false, context(2, true, swapped()), false);
        ToolSwapCommand guiRestore = only(gui);
        Assert.assertFalse(gui.onActionPreflight(guiRestore, context(2, true, swapped())));
        Assert.assertTrue(gui.hasLedger());
        Assert.assertTrue(gui.drainCommands().isEmpty());
        gui.onTick(context(3, swapped()));
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, only(gui).type);

        AutoToolSwapController cursor = completedSwap();
        cursor.onKeyState(false, context(2, swapped()), false);
        ToolSwapCommand cursorRestore = only(cursor);
        ToolSwapContext unsafeCursor = new ToolSwapContext(2, true, false, false, false, true, 0, swapped());
        Assert.assertFalse(cursor.onActionPreflight(cursorRestore, unsafeCursor));
        Assert.assertTrue(cursor.hasLedger());
        cursor.onTick(context(3, swapped()));
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, only(cursor).type);
    }

    @Test
    public void unsafeSwapPreflightRetractsLedgerAndAdvancesMatchingWatermark() {
        AutoToolSwapController controller = acceptedSwap();
        ToolSwapCommand swap = only(controller);
        ToolSwapContext unsafe = new ToolSwapContext(0, true, false, false, false, true, 0, restored());
        Assert.assertFalse(controller.onActionPreflight(swap, unsafe));
        Assert.assertFalse(controller.hasLedger());
        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
    }

    @Test
    public void restorePreflightWaitsForUntrustedInventoryButOrphansTrustedThirdLayout() {
        AutoToolSwapController untrusted = completedSwap();
        untrusted.onKeyState(false, context(2, swapped()), false);
        ToolSwapCommand waitingRestore = only(untrusted);
        Assert.assertFalse(untrusted.onActionPreflight(waitingRestore, ToolSwapInventorySnapshot.untrusted()));
        Assert.assertEquals(ToolSwapTransactionState.IDLE, untrusted.transactionState());
        Assert.assertTrue(untrusted.hasLedger());
        untrusted.onTick(context(3, swapped()));
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, only(untrusted).type);

        AutoToolSwapController released = completedSwap();
        released.onKeyState(false, context(2, swapped()), false);
        ToolSwapCommand releaseRestore = only(released);
        Assert.assertFalse(released.onActionPreflight(releaseRestore, protectedSlots(third())));
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED, released.transactionState());
        released.onTick(context(3, third()));
        Assert.assertTrue(released.drainCommands().isEmpty());

        AutoToolSwapController reanchored = completedSwap();
        reanchored.onTick(new ToolSwapContext(2, true, false, false, true, true, 1, swapped()));
        ToolSwapCommand reanchorRestore = only(reanchored);
        Assert.assertFalse(reanchored.onActionPreflight(reanchorRestore, protectedSlots(third())));
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED, reanchored.transactionState());
    }

    @Test
    public void consecutiveRestoreRejectionsAreBoundedAndFailClosed() {
        AutoToolSwapController controller = completedSwap();
        controller.onKeyState(false, context(2, swapped()), false);
        for (int rejected = 1; rejected <= AutoToolSwapController.MAX_CONSECUTIVE_RESTORE_REJECTIONS;
                rejected++) {
            ToolSwapCommand restore = only(controller);
            Assert.assertTrue(controller.onActionPreflight(restore, protectedSlots(swapped())));
            controller.onActionStarted(AutoToolSwapAction.RESTORE);
            controller.onActionSettled(AutoToolSwapAction.RESTORE, AutoToolSwapResultCode.REJECTED,
                    AutoToolSwapRoundState.CLOSING, rejected + 2L);
            if (rejected < AutoToolSwapController.MAX_CONSECUTIVE_RESTORE_REJECTIONS) {
                controller.onTick(context(rejected + 3L, swapped()));
            }
        }
        Assert.assertEquals(ToolSwapTransactionState.PROTOCOL_ORPHANED, controller.transactionState());
        controller.onTick(context(10, swapped()));
        Assert.assertTrue(controller.drainCommands().isEmpty());
    }

    @Test
    public void naturalFinishedCloseDefersOneNewGenerationButReleaseAndNonFinishedCloseDisqualifyIt() {
        AutoToolSwapController natural = acceptedRoundWithoutSwap();
        natural.onDedicatedPhase(ChainPhase.IDLE);
        ToolSwapCommand naturalClose = only(natural);
        natural.onActionStarted(AutoToolSwapAction.CLOSE);
        natural.onActionSettled(AutoToolSwapAction.CLOSE, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED, 1L);

        Assert.assertEquals(AutoToolSwapState.IDLE, natural.state());
        Assert.assertTrue("CLOSE 回调内不得立即创建下一 round", natural.drainCommands().isEmpty());
        long firstGeneration = natural.generation();
        Assert.assertTrue(natural.beginDeferredRound(context(2L, noCandidate())));
        Assert.assertEquals(firstGeneration + 1L, natural.generation());
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_ROUND, only(natural).type);

        AutoToolSwapController released = acceptedRoundWithoutSwap();
        released.onDedicatedPhase(ChainPhase.IDLE);
        ToolSwapCommand releasedClose = only(released);
        released.onActionStarted(AutoToolSwapAction.CLOSE);
        released.onActionSettled(AutoToolSwapAction.CLOSE, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED, 1L);
        released.onKeyState(false, context(2L, noCandidate()), false);
        Assert.assertFalse(released.beginDeferredRound(context(3L, noCandidate())));

        AutoToolSwapController nonFinished = acceptedRoundWithoutSwap();
        nonFinished.onDedicatedPhase(ChainPhase.IDLE);
        ToolSwapCommand nonFinishedClose = only(nonFinished);
        nonFinished.onActionStarted(AutoToolSwapAction.CLOSE);
        nonFinished.onActionSettled(AutoToolSwapAction.CLOSE, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.CLOSING, 1L);
        Assert.assertFalse(nonFinished.beginDeferredRound(context(2L, noCandidate())));
    }

    @Test
    public void releaseGateOutranksNaturalCloseWhenKeyIsQuicklyRepressed() {
        AutoToolSwapController controller = acceptedRoundWithoutSwap();
        controller.onDedicatedPhase(ChainPhase.IDLE);
        only(controller);
        controller.onKeyState(false, context(1L, noCandidate()), false);
        ToolSwapCommand close = only(controller);
        controller.onActionStarted(AutoToolSwapAction.CLOSE);
        controller.onKeyState(true, context(1L, noCandidate()), false);
        controller.onActionSettled(AutoToolSwapAction.CLOSE, AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED, 1L);

        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, controller.state());
        Assert.assertFalse(controller.beginDeferredRound(context(2L, noCandidate())));
    }

    private static AutoToolSwapController acceptedSwap() {
        AutoToolSwapController controller = controller();
        controller.onKeyState(true, context(0, restored()), false);
        only(controller);
        controller.onRoundAccepted();
        controller.onTick(context(0, restored()));
        return controller;
    }

    private static AutoToolSwapController acceptedRoundWithoutSwap() {
        AutoToolSwapController controller = controller();
        controller.onKeyState(true, context(0L, noCandidate()), false);
        only(controller);
        controller.onRoundAccepted();
        return controller;
    }

    private static void assertInapplicableCycleHasNoRound(ToolSwapContext context) {
        AutoToolSwapController controller = controller();
        controller.onKeyState(true, context, false);
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, controller.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        Assert.assertTrue(controller.drainCommands().isEmpty());
        controller.onKeyState(false, context, false);
        Assert.assertEquals(AutoToolSwapState.IDLE, controller.state());
    }

    private static AutoToolSwapController completedSwap() {
        AutoToolSwapController controller = swapWaitingForInventory();
        controller.onInventoryObserved(protectedSlots(swapped()), 1);
        return controller;
    }

    private static AutoToolSwapController startedSwap() {
        AutoToolSwapController controller = acceptedSwap();
        ToolSwapCommand command = only(controller);
        Assert.assertTrue(controller.onActionPreflight(command, protectedSlots(restored())));
        controller.onActionStarted(AutoToolSwapAction.SWAP);
        return controller;
    }

    private static AutoToolSwapController swapWaitingForInventory() {
        AutoToolSwapController controller = acceptedSwap();
        ToolSwapCommand command = only(controller);
        Assert.assertTrue(controller.onActionPreflight(command, protectedSlots(restored())));
        controller.onActionStarted(AutoToolSwapAction.SWAP);
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.SWAPPED, 0);
        return controller;
    }

    private static void settleRestore(AutoToolSwapController controller, ToolSwapCommand command, long tick) {
        Assert.assertTrue(controller.onActionPreflight(command, protectedSlots(swapped())));
        controller.onActionStarted(AutoToolSwapAction.RESTORE);
        controller.onActionSettled(AutoToolSwapAction.RESTORE, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.CLOSING, tick);
        controller.onInventoryObserved(protectedSlots(restored()), tick);
    }

    private static AutoToolSwapController controller() {
        return new AutoToolSwapController(true, Collections.emptyList(), 40);
    }

    private static ToolSwapCommand only(AutoToolSwapController controller) {
        List<ToolSwapCommand> commands = controller.drainCommands();
        Assert.assertEquals("commands=" + commands.size(), 1, commands.size());
        return commands.get(0);
    }

    private static ToolSwapContext context(long tick, ToolSwapInventorySnapshot inventory) {
        return context(tick, false, inventory);
    }

    private static ToolSwapContext context(long tick, boolean gui, ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, gui, !gui, true, 0, inventory);
    }

    private static ToolSwapContext context(long tick, int selectedSlot, ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, false, true, true, selectedSlot, inventory);
    }

    private static ToolSwapInventorySnapshot protectedSlots(ToolSwapInventorySnapshot inventory) {
        return protectedSlots(inventory, 0, 5);
    }

    private static ToolSwapInventorySnapshot protectedSlots(ToolSwapInventorySnapshot inventory,
            int anchorSlot, int candidateSlot) {
        return ToolSwapInventorySnapshot.protectedSlots(
                Arrays.asList(inventory.slot(anchorSlot), inventory.slot(candidateSlot)));
    }

    private static ToolSwapInventorySnapshot restored() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(0, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot swapped() {
        return inventory(new SlotSnapshot(0, "pick", "used"), new SlotSnapshot(5, "hand", "old"),
                tool(0, "pick", true), tool(5, "hand", false));
    }

    private static ToolSwapInventorySnapshot reanchoredRestored() {
        return inventory(new SlotSnapshot(1, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(1, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot reanchoredSwapped() {
        return inventory(new SlotSnapshot(1, "pick", "used"), new SlotSnapshot(5, "hand", "old"),
                tool(1, "pick", true), tool(5, "hand", false));
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
        java.util.ArrayList<SlotSnapshot> slots = new java.util.ArrayList<SlotSnapshot>();
        java.util.ArrayList<ToolCandidate> candidates = new java.util.ArrayList<ToolCandidate>();
        for (Object value : values) {
            if (value instanceof SlotSnapshot) slots.add((SlotSnapshot) value);
            if (value instanceof ToolCandidate) candidates.add((ToolCandidate) value);
        }
        return new ToolSwapInventorySnapshot(slots, candidates);
    }
}
