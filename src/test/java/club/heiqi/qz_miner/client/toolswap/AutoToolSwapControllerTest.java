package club.heiqi.qz_miner.client.toolswap;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.ToolSelector;
import club.heiqi.qz_miner.toolswap.ToolSelectorParser;

/** 六态核心、代际、事务与收口合同。 */
public class AutoToolSwapControllerTest {

    @Test
    public void realRisingEdgeMatchesImmediatelyAndThenUsesTenTickWatermark() {
        AutoToolSwapController controller = controller(true, Collections.<ToolSelector>emptyList());
        controller.onKeyState(true, context(0, false, restored(0, 5)));

        Assert.assertEquals(1L, controller.generation());
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_SWAP, onlyCommand(controller).type);
        controller.onKeyState(true, context(0, false, restored(0, 5)));
        Assert.assertEquals(1L, controller.generation());
        Assert.assertTrue(controller.drainCommands().isEmpty());

        AutoToolSwapController watermark = controller(true, Collections.<ToolSelector>emptyList());
        watermark.onKeyState(true, context(0, false, noCandidate(0)));
        Assert.assertTrue(watermark.drainCommands().isEmpty());
        watermark.onTick(context(9, false, restored(0, 5)));
        Assert.assertTrue(watermark.drainCommands().isEmpty());
        watermark.onTick(context(10, false, restored(0, 5)));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_SWAP, onlyCommand(watermark).type);
    }

    @Test
    public void positiveAckWaitsForLaterTickAndRoleVerificationAllowsToolMutation() {
        AutoToolSwapController controller = startSwap();
        long generation = controller.generation();
        controller.onPacketIdAssigned(generation, 7, 0);
        controller.onTransactionAck(generation, 7, true, 1);

        controller.onTick(context(1, false, swapped(0, 5, "damage=1")));
        Assert.assertEquals(ToolSwapTransactionState.WAIT_SYNC_TICK, controller.transactionState());
        controller.onTick(context(2, false, swapped(0, 5, "damage=2;nbt=changed")));
        Assert.assertEquals(ToolSwapCommand.Type.VERIFY_SLOTS, onlyCommand(controller).type);
        controller.onSlotsObserved(generation, 7, swapped(0, 5, "new-instance"));

        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        Assert.assertTrue(controller.hasLedger());
    }

    @Test
    public void releaseDuringSwapRestoresBeforeClosingAndAcceptsMutatedActiveTool() {
        AutoToolSwapController controller = startSwap();
        long generation = controller.generation();
        controller.onPacketIdAssigned(generation, 11, 0);
        controller.onKeyState(false, context(1, false, swapped(0, 5, "damage=3")));
        controller.onTransactionAck(generation, 11, true, 1);
        controller.onTick(context(2, false, swapped(0, 5, "damage=4")));
        onlyCommand(controller);
        controller.onSlotsObserved(generation, 11, swapped(0, 5, "damage=5"));

        Assert.assertTrue("slot callback without current context must only register restore",
                controller.drainCommands().isEmpty());
        controller.onTick(context(3, false, swapped(0, 5, "damage=5")));
        ToolSwapCommand restore = onlyCommand(controller);
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_RESTORE, restore.type);
        Assert.assertEquals(AutoToolSwapState.RESTORING, controller.state());
        controller.onPacketIdAssigned(generation, 12, 3);
        controller.onTransactionAck(generation, 12, true, 3);
        controller.onTick(context(4, false, restoredMutated(0, 5)));
        onlyCommand(controller);
        controller.onSlotsObserved(generation, 12, restoredMutated(0, 5));

        Assert.assertEquals(AutoToolSwapState.IDLE, controller.state());
        Assert.assertFalse(controller.hasLedger());
    }

    @Test
    public void rapidReleaseAndRepressDuringRestoreClosesIntoWaitRelease() {
        AutoToolSwapController controller = completeInitialSwap();
        long generation = controller.generation();
        controller.onKeyState(false, context(2, false, swapped(0, 5, "used")));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_RESTORE, onlyCommand(controller).type);
        controller.onKeyState(true, context(2, false, swapped(0, 5, "used")));

        finishTransaction(controller, 22, 2, context(3, false, restoredMutated(0, 5)));

        Assert.assertEquals(generation, controller.generation());
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, controller.state());
        controller.onKeyState(false, context(4, false, restoredMutated(0, 5)));
        controller.onKeyState(true, context(5, false, noCandidate(0)));
        Assert.assertEquals(generation + 1L, controller.generation());
    }

    @Test
    public void staleSignalsCannotFreezeOrReplaceCurrentGeneration() {
        AutoToolSwapController controller = controller(true, Collections.<ToolSelector>emptyList());
        controller.onKeyState(true, context(0, false, noCandidate(0)));
        long first = controller.generation();
        controller.onKeyState(false, context(1, false, noCandidate(0)));
        controller.onKeyState(true, context(2, false, noCandidate(0)));

        controller.onLocalBlockDestroyed(first);
        controller.onServerActivity(first);

        Assert.assertEquals(first + 1L, controller.generation());
        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
        controller.onLocalBlockDestroyed(controller.generation());
        Assert.assertEquals(AutoToolSwapState.FROZEN, controller.state());
        controller.onTick(context(100, false, restored(0, 5)));
        Assert.assertTrue("frozen cycle must not rematch", controller.drainCommands().isEmpty());
    }

    @Test
    public void manualReanchorRestoresOldLedgerBeforeSelectingForNewAnchor() {
        AutoToolSwapController controller = completeInitialSwap();
        controller.onTick(contextSelected(3, 1, swapped(0, 5, "used")));

        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_RESTORE, onlyCommand(controller).type);
        finishTransaction(controller, 31, 3, contextSelected(4, 1, restored(0, 5)));
        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
        controller.onTick(contextSelected(4, 1, restoredForAnchorOne()));

        ToolSwapCommand swap = onlyCommand(controller);
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_SWAP, swap.type);
        Assert.assertEquals(1, swap.anchorSlot);
        Assert.assertEquals(5, swap.candidateSlot);
    }

    @Test
    public void manualSelectionAfterFreezeStillRestoresButNeverRematches() {
        AutoToolSwapController controller = completeInitialSwap();
        controller.onLocalBlockDestroyed(controller.generation());
        Assert.assertEquals(AutoToolSwapState.FROZEN, controller.state());

        controller.onTick(contextSelected(3, 1, swapped(0, 5, "used")));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_RESTORE, onlyCommand(controller).type);
        finishTransaction(controller, 32, 3, contextSelected(4, 1, restored(0, 5)));

        Assert.assertEquals(AutoToolSwapState.FROZEN, controller.state());
        controller.onTick(contextSelected(100, 1, restoredForAnchorOne()));
        Assert.assertTrue(controller.drainCommands().isEmpty());
    }

    @Test
    public void negativeAckTimeoutAndProtectedSlotMismatchEnterIsolationWithoutRetry() {
        AutoToolSwapController rejected = startSwap();
        rejected.onPacketIdAssigned(rejected.generation(), 40, 0);
        rejected.onTransactionAck(rejected.generation(), 40, false, 1);
        assertIsolated(rejected);
        rejected.onTick(context(100, false, restored(0, 5)));
        Assert.assertTrue(rejected.drainCommands().isEmpty());

        AutoToolSwapController timedOut = new AutoToolSwapController(true,
                Collections.<ToolSelector>emptyList(), 3);
        timedOut.onKeyState(true, context(0, false, restored(0, 5)));
        onlyCommand(timedOut);
        timedOut.onTick(context(3, false, restored(0, 5)));
        assertIsolated(timedOut);

        AutoToolSwapController mismatch = startSwap();
        mismatch.onPacketIdAssigned(mismatch.generation(), 41, 0);
        mismatch.onTransactionAck(mismatch.generation(), 41, true, 0);
        mismatch.onTick(context(1, false, thirdPartyChanged(0, 5)));
        onlyCommand(mismatch);
        mismatch.onSlotsObserved(mismatch.generation(), 41, thirdPartyChanged(0, 5));
        assertIsolated(mismatch);
    }

    @Test
    public void displacedOriginalHandRequiresStrictFingerprintButActiveToolMayBreakToEmpty() {
        AutoToolSwapController replacedOriginal = startSwap();
        replacedOriginal.onPacketIdAssigned(replacedOriginal.generation(), 42, 0);
        replacedOriginal.onTransactionAck(replacedOriginal.generation(), 42, true, 0);
        ToolSwapInventorySnapshot changedOriginal = inventory(
                slot(0, "pick", "used"), slot(5, "hand", "third-party-nbt"),
                tool(0, "mod:pick", true, 90), tool(5, "mod:hand", false, 100));
        replacedOriginal.onTick(context(1, false, changedOriginal));
        onlyCommand(replacedOriginal);
        replacedOriginal.onSlotsObserved(replacedOriginal.generation(), 42, changedOriginal);
        assertIsolated(replacedOriginal);

        AutoToolSwapController brokenTool = startSwap();
        brokenTool.onPacketIdAssigned(brokenTool.generation(), 43, 0);
        brokenTool.onTransactionAck(brokenTool.generation(), 43, true, 0);
        ToolSwapInventorySnapshot brokenLayout = inventory(
                slot(0, SlotSnapshot.EMPTY_ROLE_KEY, ""), slot(5, "hand", "old"),
                tool(5, "mod:hand", false, 100));
        brokenTool.onTick(context(1, false, brokenLayout));
        onlyCommand(brokenTool);
        brokenTool.onSlotsObserved(brokenTool.generation(), 43, brokenLayout);
        Assert.assertEquals(ToolSwapTransactionState.IDLE, brokenTool.transactionState());
        Assert.assertTrue(brokenTool.hasLedger());
    }

    @Test
    public void unsafeInventoryGateDefersSwapWithoutStartingTimeout() {
        AutoToolSwapController controller = new AutoToolSwapController(true,
                Collections.<ToolSelector>emptyList(), 3);
        controller.onKeyState(true, context(0, false, false, restored(0, 5)));

        Assert.assertTrue(controller.drainCommands().isEmpty());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        controller.onTick(context(100, false, false, restored(0, 5)));
        Assert.assertEquals(AutoToolSwapState.PREPARING, controller.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        Assert.assertTrue(controller.drainCommands().isEmpty());

        controller.onTick(context(101, false, true, restored(0, 5)));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_SWAP, onlyCommand(controller).type);
        Assert.assertEquals(ToolSwapTransactionState.WAIT_PACKET_ID, controller.transactionState());
    }

    @Test
    public void guiWithUnsafeContainerDefersRestoreWithoutStartingTimeout() {
        AutoToolSwapController controller = completeInitialSwap();
        controller.onTick(context(2, true, false, swapped(0, 5, "used")));

        Assert.assertEquals(AutoToolSwapState.RESTORING, controller.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        Assert.assertTrue(controller.drainCommands().isEmpty());
        controller.onTick(context(100, true, false, swapped(0, 5, "used")));
        Assert.assertEquals(AutoToolSwapState.RESTORING, controller.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, controller.transactionState());
        Assert.assertTrue(controller.drainCommands().isEmpty());

        controller.onTick(context(101, true, true, swapped(0, 5, "used")));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_RESTORE, onlyCommand(controller).type);
        Assert.assertEquals(ToolSwapTransactionState.WAIT_PACKET_ID, controller.transactionState());
    }

    @Test
    public void synchronizationRecoveryUsesStableLayoutAndRetainsRecoveryDuty() {
        AutoToolSwapController restored = isolateInitialSwapAfterAckLoss(restored(0, 5));
        restored.onSynchronizationRecovered(restored(0, 5));
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, restored.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, restored.transactionState());
        Assert.assertFalse(restored.hasLedger());
        Assert.assertTrue(restored.drainCommands().isEmpty());

        AutoToolSwapController unknown = isolateInitialSwapAfterAckLoss(thirdPartyChanged(0, 5));
        long isolatedGeneration = unknown.generation();
        unknown.onSynchronizationRecovered(thirdPartyChanged(0, 5));
        unknown.onKeyState(false, context(4, false, thirdPartyChanged(0, 5)));
        unknown.onKeyState(true, context(5, false, restored(0, 5)));
        unknown.onTransactionAck(isolatedGeneration, 71, true, 5);
        unknown.onLocalBlockDestroyed(isolatedGeneration);
        assertIsolated(unknown);
        Assert.assertEquals(isolatedGeneration, unknown.generation());
        Assert.assertTrue(unknown.hasLedger());

        AutoToolSwapController swapped = isolateInitialSwapAfterAckLoss(swapped(0, 5, "server-applied"));
        swapped.onSynchronizationRecovered(swapped(0, 5, "stable"));
        Assert.assertEquals(AutoToolSwapState.RESTORING, swapped.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, swapped.transactionState());
        Assert.assertTrue(swapped.hasLedger());
        Assert.assertTrue(swapped.drainCommands().isEmpty());
        swapped.onTick(context(100, false, false, swapped(0, 5, "stable")));
        Assert.assertEquals(ToolSwapTransactionState.IDLE, swapped.transactionState());
        Assert.assertTrue("unsafe tick after recovery must not start restore",
                swapped.drainCommands().isEmpty());
        swapped.onTick(context(101, false, true, swapped(0, 5, "stable")));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_RESTORE, onlyCommand(swapped).type);
        swapped.onSynchronizationRecovered(swapped(0, 5, "stable"));
        Assert.assertTrue("stable swapped layout must request restore only once",
                swapped.drainCommands().isEmpty());

        finishTransaction(swapped, 72, 101, context(102, false, restoredMutated(0, 5)));
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, swapped.state());
        Assert.assertFalse(swapped.hasLedger());
    }

    @Test
    public void guiPausesMatchingAndChainEndWaitsForRelease() {
        AutoToolSwapController controller = controller(true, Collections.<ToolSelector>emptyList());
        controller.onKeyState(true, guiContext(0, restored(0, 5)));
        Assert.assertTrue(controller.drainCommands().isEmpty());
        controller.onTick(context(1, false, restored(0, 5)));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_SWAP, onlyCommand(controller).type);

        AutoToolSwapController ended = controller(true, Collections.<ToolSelector>emptyList());
        ended.onKeyState(true, context(0, false, noCandidate(0)));
        ended.onTick(new ToolSwapContext(1, true, false, false, true, false, 0, noCandidate(0)));
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, ended.state());
        ended.onKeyState(true, context(2, false, noCandidate(0)));
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, ended.state());
        ended.onKeyState(false, context(3, false, noCandidate(0)));
        Assert.assertEquals(AutoToolSwapState.IDLE, ended.state());
    }

    @Test
    public void enableAndSelectorChangesAreCapturedOnlyAtNextRisingEdgeButDisableClosesNow() {
        AutoToolSwapController disabled = controller(false, Collections.<ToolSelector>emptyList());
        disabled.onKeyState(true, context(0, false, restored(0, 5)));
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, disabled.state());
        disabled.onConfigChanged(true,
                Collections.singletonList(ToolSelectorParser.parse("mod:pick@*")));
        disabled.onTick(context(10, false, restored(0, 5)));
        Assert.assertTrue(disabled.drainCommands().isEmpty());
        disabled.onKeyState(false, context(11, false, restored(0, 5)));
        disabled.onKeyState(true, context(12, false, restored(0, 5)));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_SWAP, onlyCommand(disabled).type);

        AutoToolSwapController active = completeInitialSwap();
        active.onConfigChanged(false, Collections.<ToolSelector>emptyList());
        Assert.assertEquals(AutoToolSwapState.RESTORING, active.state());
        Assert.assertEquals(ToolSwapTransactionState.IDLE, active.transactionState());
        Assert.assertTrue("config callback without current context must only register restore",
                active.drainCommands().isEmpty());
        active.onTick(context(100, false, false, swapped(0, 5, "used")));
        Assert.assertEquals(ToolSwapTransactionState.IDLE, active.transactionState());
        Assert.assertTrue("unsafe tick after config close must not start restore",
                active.drainCommands().isEmpty());
        active.onTick(context(101, false, true, swapped(0, 5, "used")));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_RESTORE, onlyCommand(active).type);
        Assert.assertEquals(ToolSwapTransactionState.WAIT_PACKET_ID, active.transactionState());
    }

    @Test
    public void selectorReloadDoesNotReorderCurrentCycleButAppliesAfterNextEdge() {
        ToolSelector pickFirst = ToolSelectorParser.parse("mod:pick@*");
        ToolSelector axeFirst = ToolSelectorParser.parse("mod:axe@*");
        AutoToolSwapController controller = controller(true, Collections.singletonList(pickFirst));
        controller.onKeyState(true, context(0, false, noCandidate(0)));
        controller.onConfigChanged(true, Collections.singletonList(axeFirst));

        controller.onTick(context(10, false, twoCandidates()));
        Assert.assertEquals("current cycle must retain old selector snapshot", 5,
                onlyCommand(controller).candidateSlot);
        finishTransaction(controller, 50, 10, context(11, false, swapped(0, 5, "used")));
        controller.onKeyState(false, context(12, false, swapped(0, 5, "used")));
        onlyCommand(controller);
        finishTransaction(controller, 51, 12, context(13, false, twoCandidates()));

        controller.onKeyState(true, context(14, false, twoCandidates()));
        Assert.assertEquals("next edge must capture reloaded selector snapshot", 3,
                onlyCommand(controller).candidateSlot);
    }

    private static AutoToolSwapController startSwap() {
        AutoToolSwapController controller = controller(true, Collections.<ToolSelector>emptyList());
        controller.onKeyState(true, context(0, false, restored(0, 5)));
        Assert.assertEquals(ToolSwapCommand.Type.BEGIN_SWAP, onlyCommand(controller).type);
        return controller;
    }

    private static AutoToolSwapController completeInitialSwap() {
        AutoToolSwapController controller = startSwap();
        finishTransaction(controller, 20, 0, context(1, false, swapped(0, 5, "used")));
        Assert.assertTrue(controller.hasLedger());
        return controller;
    }

    private static AutoToolSwapController isolateInitialSwapAfterAckLoss(
            ToolSwapInventorySnapshot stableInventory) {
        AutoToolSwapController controller = new AutoToolSwapController(true,
                Collections.<ToolSelector>emptyList(), 3);
        controller.onKeyState(true, context(0, false, restored(0, 5)));
        onlyCommand(controller);
        controller.onPacketIdAssigned(controller.generation(), 71, 0);
        controller.onTick(context(3, false, stableInventory));
        assertIsolated(controller);
        Assert.assertTrue(controller.hasLedger());
        return controller;
    }

    private static void finishTransaction(AutoToolSwapController controller, int packetId, long tick,
            ToolSwapContext finalContext) {
        controller.onPacketIdAssigned(controller.generation(), packetId, tick);
        controller.onTransactionAck(controller.generation(), packetId, true, tick);
        controller.onTick(finalContext);
        Assert.assertEquals(ToolSwapCommand.Type.VERIFY_SLOTS, onlyCommand(controller).type);
        controller.onSlotsObserved(controller.generation(), packetId, finalContext.inventory);
    }

    private static void assertIsolated(AutoToolSwapController controller) {
        Assert.assertEquals(AutoToolSwapState.ABORTED_SYNC, controller.state());
        Assert.assertEquals(ToolSwapTransactionState.SYNC_ISOLATION, controller.transactionState());
        Assert.assertTrue(controller.drainCommands().isEmpty());
    }

    private static ToolSwapCommand onlyCommand(AutoToolSwapController controller) {
        List<ToolSwapCommand> commands = controller.drainCommands();
        Assert.assertEquals("commands=" + commands.size(), 1, commands.size());
        return commands.get(0);
    }

    private static AutoToolSwapController controller(boolean enabled, List<ToolSelector> selectors) {
        return new AutoToolSwapController(enabled, selectors, 20);
    }

    private static ToolSwapContext context(long tick, boolean gui, ToolSwapInventorySnapshot inventory) {
        return context(tick, gui, true, inventory);
    }

    private static ToolSwapContext context(long tick, boolean gui, boolean transactionSafe,
            ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, gui, transactionSafe, true, 0, inventory);
    }

    private static ToolSwapContext contextSelected(long tick, int selected, ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, false, true, true, selected, inventory);
    }

    private static ToolSwapContext guiContext(long tick, ToolSwapInventorySnapshot inventory) {
        return context(tick, true, false, inventory);
    }

    private static ToolSwapInventorySnapshot restored(int anchor, int candidate) {
        return inventory(slot(anchor, "hand", "old"), slot(candidate, "pick", "fresh"),
                tool(anchor, "mod:hand", false, 100), tool(candidate, "mod:pick", true, 100));
    }

    private static ToolSwapInventorySnapshot restoredMutated(int anchor, int candidate) {
        return inventory(slot(anchor, "hand", "old"), slot(candidate, "pick", "damage=9"),
                tool(anchor, "mod:hand", false, 100), tool(candidate, "mod:pick", true, 91));
    }

    private static ToolSwapInventorySnapshot swapped(int anchor, int candidate, String dynamic) {
        return inventory(slot(anchor, "pick", dynamic), slot(candidate, "hand", "old"),
                tool(anchor, "mod:pick", true, 90), tool(candidate, "mod:hand", false, 100));
    }

    private static ToolSwapInventorySnapshot thirdPartyChanged(int anchor, int candidate) {
        return inventory(slot(anchor, "pick", "ok"), slot(candidate, "third-party", "changed"),
                tool(anchor, "mod:pick", true, 90));
    }

    private static ToolSwapInventorySnapshot noCandidate(int anchor) {
        return inventory(slot(anchor, "hand", "old"), tool(anchor, "mod:hand", false, 100));
    }

    private static ToolSwapInventorySnapshot restoredForAnchorOne() {
        return inventory(slot(0, "hand", "old"), slot(1, "empty", ""), slot(5, "pick", "fresh"),
                tool(1, "mod:empty", false, 100), tool(5, "mod:pick", true, 90));
    }

    private static ToolSwapInventorySnapshot twoCandidates() {
        return inventory(slot(0, "hand", "old"), slot(3, "axe", "fresh"), slot(5, "pick", "fresh"),
                tool(0, "mod:hand", false, 100), tool(3, "mod:axe", true, 90),
                tool(5, "mod:pick", true, 90));
    }

    private static ToolSwapInventorySnapshot inventory(Object... values) {
        java.util.ArrayList<SlotSnapshot> slots = new java.util.ArrayList<SlotSnapshot>();
        java.util.ArrayList<ToolCandidate> tools = new java.util.ArrayList<ToolCandidate>();
        for (Object value : values) {
            if (value instanceof SlotSnapshot) {
                slots.add((SlotSnapshot) value);
            } else {
                tools.add((ToolCandidate) value);
            }
        }
        return new ToolSwapInventorySnapshot(slots, tools);
    }

    private static SlotSnapshot slot(int slot, String role, String dynamic) {
        return new SlotSnapshot(slot, role, dynamic);
    }

    private static ToolCandidate tool(int slot, String id, boolean usable, int durability) {
        return new ToolCandidate(slot, id, 0, Arrays.asList("toolPickaxe"),
                usable, usable, durability);
    }
}
