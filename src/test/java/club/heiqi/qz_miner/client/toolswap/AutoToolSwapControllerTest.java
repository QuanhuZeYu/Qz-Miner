package club.heiqi.qz_miner.client.toolswap;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;

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
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED, 0);
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
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED, 5);
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
    public void rejectedRestoreRetainsLedgerAndPreflightFailureRetractsSwap() {
        AutoToolSwapController restore = completedSwap();
        restore.onKeyState(false, context(2, swapped()), false);
        ToolSwapCommand command = only(restore);
        Assert.assertTrue(restore.onActionPreflight(command, protectedSlots(swapped())));
        restore.onActionStarted(AutoToolSwapAction.RESTORE);
        restore.onActionSettled(AutoToolSwapAction.RESTORE, AutoToolSwapResultCode.REJECTED, 2);
        restore.onTick(context(3, swapped()));
        Assert.assertEquals(ToolSwapCommand.Type.SEND_RESTORE, only(restore).type);

        AutoToolSwapController swap = acceptedSwap();
        ToolSwapCommand swapCommand = only(swap);
        Assert.assertFalse(swap.onActionPreflight(swapCommand, protectedSlots(noCandidate())));
        Assert.assertFalse(swap.hasLedger());
        Assert.assertEquals(AutoToolSwapState.PREPARING, swap.state());
    }

    private static AutoToolSwapController acceptedSwap() {
        AutoToolSwapController controller = controller();
        controller.onKeyState(true, context(0, restored()), false);
        only(controller);
        controller.onRoundAccepted();
        controller.onTick(context(0, restored()));
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

    private static AutoToolSwapController swapWaitingForInventory() {
        AutoToolSwapController controller = acceptedSwap();
        ToolSwapCommand command = only(controller);
        Assert.assertTrue(controller.onActionPreflight(command, protectedSlots(restored())));
        controller.onActionStarted(AutoToolSwapAction.SWAP);
        controller.onActionSettled(AutoToolSwapAction.SWAP, AutoToolSwapResultCode.APPLIED, 0);
        return controller;
    }

    private static void settleRestore(AutoToolSwapController controller, ToolSwapCommand command, long tick) {
        Assert.assertTrue(controller.onActionPreflight(command, protectedSlots(swapped())));
        controller.onActionStarted(AutoToolSwapAction.RESTORE);
        controller.onActionSettled(AutoToolSwapAction.RESTORE, AutoToolSwapResultCode.APPLIED, tick);
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

    private static ToolSwapInventorySnapshot protectedSlots(ToolSwapInventorySnapshot inventory) {
        return ToolSwapInventorySnapshot.protectedSlots(Arrays.asList(inventory.slot(0), inventory.slot(5)));
    }

    private static ToolSwapInventorySnapshot restored() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(0, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot swapped() {
        return inventory(new SlotSnapshot(0, "pick", "used"), new SlotSnapshot(5, "hand", "old"),
                tool(0, "pick", true), tool(5, "hand", false));
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
