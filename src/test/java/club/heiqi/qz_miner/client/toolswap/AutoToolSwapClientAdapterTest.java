package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.ClientConnectionLifecycle;
import club.heiqi.qz_miner.toolswap.ToolCandidate;

/** adapter 的两阶段采样、原版事务、phase 归因与 lifecycle 合同。 */
public class AutoToolSwapClientAdapterTest {

    private Object handler;
    private Object world;
    private FakeGame game;
    private FakePhase phase;
    private AutoToolSwapClientAdapter adapter;

    @Before
    public void setUp() {
        handler = new Object();
        world = new Object();
        ClientConnectionLifecycle.connect(handler);
        ClientConnectionLifecycle.bindWorld(world);
        game = new FakeGame(handler);
        phase = new FakePhase(ChainPhase.IDLE, 5);
        adapter = new AutoToolSwapClientAdapter(true, Collections.emptyList(), game, phase);
        game.adapter = adapter;
    }

    @After
    public void tearDown() {
        ClientConnectionLifecycle.unbindWorld(world);
        ClientConnectionLifecycle.disconnect(handler);
    }

    @Test
    public void risingEdgeUsesSingleMode2ClickWithRealPacketIdAndKeepsSelectedSlot() {
        game.inventory = restored();
        adapter.onChainKeyState(true);

        Assert.assertEquals(1, game.vanillaCalls);
        Assert.assertEquals(41, game.lastContainerSlot);
        Assert.assertEquals(0, game.lastButton);
        Assert.assertEquals(0, game.selectedHotbarSlot);
        Assert.assertEquals(ToolSwapTransactionState.WAIT_ACK, adapter.controllerForTests().transactionState());

        game.inventory = swapped("used");
        adapter.onTransactionAck(ClientConnectionLifecycle.capture(), 0, 70, true);
        adapter.onClientTick();
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.IDLE, adapter.controllerForTests().transactionState());
        Assert.assertTrue(adapter.controllerForTests().hasLedger());

        adapter.onChainKeyState(false);
        Assert.assertEquals(2, game.vanillaCalls);
        Assert.assertEquals(41, game.lastContainerSlot);
        Assert.assertEquals(0, game.selectedHotbarSlot);
    }

    @Test
    public void preEdgeDestroyLatchFreezesAtomicallyAndDoesNotCrossTickOrLifecycle() {
        game.inventory = restored();
        game.physicalKeyDown = true;
        adapter.onLocalBlockDestroyed();
        adapter.onChainKeyState(true);

        Assert.assertEquals(AutoToolSwapState.FROZEN, adapter.controllerForTests().state());
        Assert.assertEquals(0, game.vanillaCalls);
        Assert.assertEquals(0, game.fullCaptures);

        adapter.resetForLifecycle();
        game.resetCounts();
        game.inventory = restored();
        adapter.onLocalBlockDestroyed();
        adapter.onClientTick();
        adapter.onChainKeyState(true);
        Assert.assertEquals(1, game.vanillaCalls);

        adapter.resetForLifecycle();
        game.resetCounts();
        adapter.onLocalBlockDestroyed();
        ClientConnectionLifecycle.unbindWorld(world);
        world = new Object();
        ClientConnectionLifecycle.bindWorld(world);
        adapter.onChainKeyState(true);
        Assert.assertEquals(1, game.vanillaCalls);
    }

    @Test
    public void oldPlanningAndIdleCannotAttributeUntilNewArmedIsObserved() {
        game.inventory = noCandidate();
        adapter.onChainKeyState(true);

        phase.set(ChainPhase.PLANNING, 6);
        adapter.onClientTick();
        phase.set(ChainPhase.IDLE, 6);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.PREPARING, adapter.controllerForTests().state());

        phase.set(ChainPhase.ARMED, 6);
        adapter.onClientTick();
        phase.set(ChainPhase.PLANNING, 7);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.FROZEN, adapter.controllerForTests().state());

        phase.set(ChainPhase.IDLE, 7);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, adapter.controllerForTests().state());
    }

    @Test
    public void baselineArmedMustLeaveAndObserveArmedAgain() {
        phase.set(ChainPhase.ARMED, 5);
        game.inventory = noCandidate();
        adapter.onChainKeyState(true);

        phase.set(ChainPhase.PLANNING, 6);
        adapter.onClientTick();
        phase.set(ChainPhase.IDLE, 6);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.PREPARING, adapter.controllerForTests().state());

        phase.set(ChainPhase.ARMED, 6);
        adapter.onClientTick();
        phase.set(ChainPhase.RUNNING, 7);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.FROZEN, adapter.controllerForTests().state());
    }

    @Test
    public void finalPreflightFailureNeverCallsVanillaOrStartsTimeout() {
        game.inventory = restored();
        game.finalPreflightPass = false;
        adapter.onChainKeyState(true);

        Assert.assertEquals(0, game.vanillaCalls);
        Assert.assertEquals(ToolSwapTransactionState.IDLE, adapter.controllerForTests().transactionState());
        for (int tick = 0; tick < AutoToolSwapClientAdapter.TRANSACTION_TIMEOUT_TICKS + 2; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(ToolSwapTransactionState.IDLE, adapter.controllerForTests().transactionState());
        Assert.assertNotEquals(AutoToolSwapState.ABORTED_SYNC, adapter.controllerForTests().state());
    }

    @Test
    public void vanillaExceptionAfterCallEntersIsolation() {
        game.inventory = restored();
        game.throwAfterVanilla = true;
        adapter.onChainKeyState(true);

        Assert.assertEquals(1, game.vanillaCalls);
        Assert.assertEquals(ToolSwapTransactionState.SYNC_ISOLATION,
                adapter.controllerForTests().transactionState());
        Assert.assertEquals(AutoToolSwapState.ABORTED_SYNC, adapter.controllerForTests().state());
    }

    @Test
    public void vanillaCallWithoutAttributablePacketEntersIsolation() {
        game.inventory = restored();
        game.emitPacket = false;
        adapter.onChainKeyState(true);

        Assert.assertEquals(1, game.vanillaCalls);
        Assert.assertEquals(ToolSwapTransactionState.SYNC_ISOLATION,
                adapter.controllerForTests().transactionState());
    }

    @Test
    public void everyGuiBlocksMode2ButInflightTransactionStillTimesOut() {
        game.inventory = restored();
        game.guiOpen = true;
        adapter.onChainKeyState(true);
        for (int tick = 0; tick < 15; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(0, game.vanillaCalls);
        Assert.assertEquals(0, game.fullCaptures);

        game.guiOpen = false;
        adapter.onClientTick();
        Assert.assertEquals(1, game.vanillaCalls);
        game.guiOpen = true;
        for (int tick = 0; tick <= AutoToolSwapClientAdapter.TRANSACTION_TIMEOUT_TICKS; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(1, game.vanillaCalls);
        Assert.assertEquals(ToolSwapTransactionState.SYNC_ISOLATION,
                adapter.controllerForTests().transactionState());
    }

    @Test
    public void changedOrMissingInitialCandidateCancelsWithoutClickUntilWatermark() {
        game.inventory = restored();
        game.replaceCandidateBeforeProtectedCapture = true;
        adapter.onChainKeyState(true);

        Assert.assertEquals(0, game.vanillaCalls);
        Assert.assertFalse(adapter.controllerForTests().hasLedger());
        Assert.assertEquals(AutoToolSwapState.PREPARING, adapter.controllerForTests().state());
        int fullAfterEdge = game.fullCaptures;
        for (int tick = 0; tick < 10; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(fullAfterEdge, game.fullCaptures);
    }

    @Test
    public void untrustedCaptureProducesNoClickAndExistingTransactionUsesProtectedOnly() {
        game.inventory = restored();
        game.untrustedFull = true;
        adapter.onChainKeyState(true);
        Assert.assertEquals(0, game.vanillaCalls);

        adapter.resetForLifecycle();
        game.resetCounts();
        game.untrustedFull = false;
        game.inventory = restored();
        adapter.onChainKeyState(true);
        Assert.assertEquals(1, game.fullCaptures);
        for (int tick = 0; tick < 5; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(1, game.fullCaptures);
        Assert.assertTrue(game.protectedCaptures >= 5);

        game.inventory = swapped("used");
        adapter.onTransactionAck(ClientConnectionLifecycle.capture(), 0, 70, true);
        game.untrustedProtected = true;
        adapter.onClientTick();
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.VERIFY_SLOTS,
                adapter.controllerForTests().transactionState());
        Assert.assertNotEquals(AutoToolSwapState.ABORTED_SYNC, adapter.controllerForTests().state());
    }

    @Test
    public void capturePlanBoundsFullScansForIdleAndPreparingCycles() {
        for (int tick = 0; tick < 100; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(0, game.fullCaptures);

        game.inventory = noCandidate();
        adapter.onChainKeyState(true);
        for (int tick = 0; tick < 100; tick++) {
            adapter.onClientTick();
        }
        Assert.assertTrue("full=" + game.fullCaptures, game.fullCaptures <= 11);
    }

    @Test
    public void lifecycleResetInvalidatesOldGenerationAndDoesNotSendRecoveryClick() {
        game.inventory = restored();
        adapter.onChainKeyState(true);
        long oldGeneration = adapter.controllerForTests().generation();
        Assert.assertEquals(1, game.vanillaCalls);

        adapter.resetForLifecycle();
        adapter.onTransactionAck(ClientConnectionLifecycle.capture(), 0, 70, true);
        adapter.controllerForTests().onLocalBlockDestroyed(oldGeneration);
        adapter.onClientTick();

        Assert.assertEquals(AutoToolSwapState.IDLE, adapter.controllerForTests().state());
        Assert.assertEquals(1, game.vanillaCalls);
        Assert.assertFalse(adapter.controllerForTests().hasLedger());
    }

    @Test
    public void fullWindowSynchronizationNeedsOneQuietTickBeforeIsolationRecovery() {
        game.inventory = restored();
        adapter.onChainKeyState(true);
        for (int tick = 0; tick <= AutoToolSwapClientAdapter.TRANSACTION_TIMEOUT_TICKS; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(ToolSwapTransactionState.SYNC_ISOLATION,
                adapter.controllerForTests().transactionState());

        adapter.onWindowItems(ClientConnectionLifecycle.capture(), 0);
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.SYNC_ISOLATION,
                adapter.controllerForTests().transactionState());
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.IDLE, adapter.controllerForTests().transactionState());
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, adapter.controllerForTests().state());
        Assert.assertEquals(1, game.vanillaCalls);
    }

    private static ToolSwapInventorySnapshot restored() {
        return inventory(new SlotSnapshot(0, "hand", "strict"), new SlotSnapshot(5, "pick", "fresh"),
                tool(0, "mod:hand", false), tool(5, "mod:pick", true));
    }

    private static ToolSwapInventorySnapshot swapped(String dynamic) {
        return inventory(new SlotSnapshot(0, "pick", dynamic), new SlotSnapshot(5, "hand", "strict"),
                tool(0, "mod:pick", true), tool(5, "mod:hand", false));
    }

    private static ToolSwapInventorySnapshot noCandidate() {
        return inventory(new SlotSnapshot(0, "hand", "strict"), tool(0, "mod:hand", false));
    }

    private static ToolCandidate tool(int slot, String id, boolean usable) {
        return new ToolCandidate(slot, id, 0, Arrays.asList("toolPickaxe"), usable, usable, 100);
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

    private static final class FakePhase implements AutoToolSwapClientAdapter.PhaseSource {
        private ToolSwapPhaseSnapshot snapshot;
        private FakePhase(ChainPhase phase, int generation) { set(phase, generation); }
        private void set(ChainPhase phase, int generation) {
            snapshot = new ToolSwapPhaseSnapshot(phase, generation);
        }
        @Override public ToolSwapPhaseSnapshot snapshot() { return snapshot; }
    }

    private static final class FakeGame implements AutoToolSwapClientAdapter.GameFacade {
        private final Object handler;
        private AutoToolSwapClientAdapter adapter;
        private ToolSwapInventorySnapshot inventory = noCandidate();
        private int selectedHotbarSlot;
        private boolean guiOpen;
        private boolean physicalKeyDown;
        private boolean finalPreflightPass = true;
        private boolean throwAfterVanilla;
        private boolean untrustedFull;
        private boolean untrustedProtected;
        private boolean replaceCandidateBeforeProtectedCapture;
        private boolean emitPacket = true;
        private int fullCaptures;
        private int protectedCaptures;
        private int noneCaptures;
        private int vanillaCalls;
        private int lastContainerSlot;
        private int lastButton;
        private int nextAction = 70;

        private FakeGame(Object handler) { this.handler = handler; }

        @Override
        public ToolSwapLightContext captureLightContext(long tick, boolean chainActive) {
            return new ToolSwapLightContext(tick, true, false, guiOpen, chainActive, selectedHotbarSlot);
        }

        @Override
        public ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
                int anchorSlot, int candidateSlot) {
            ToolSwapInventorySnapshot captured;
            if (plan == ToolSwapCapturePlan.FULL) {
                fullCaptures++;
                captured = untrustedFull ? ToolSwapInventorySnapshot.untrusted() : inventory;
            } else if (plan == ToolSwapCapturePlan.PROTECTED) {
                protectedCaptures++;
                if (untrustedProtected) {
                    return new ToolSwapContext(light, false, guiOpen,
                            selectedHotbarSlot, ToolSwapInventorySnapshot.untrusted());
                }
                if (replaceCandidateBeforeProtectedCapture) {
                    replaceCandidateBeforeProtectedCapture = false;
                    inventory = inventory(new SlotSnapshot(0, "hand", "strict"),
                            new SlotSnapshot(5, SlotSnapshot.EMPTY_ROLE_KEY, ""),
                            tool(0, "mod:hand", false));
                }
                ArrayList<SlotSnapshot> slots = new ArrayList<SlotSnapshot>();
                SlotSnapshot anchor = inventory.slot(anchorSlot);
                SlotSnapshot candidate = inventory.slot(candidateSlot);
                if (anchor != null) slots.add(anchor);
                if (candidate != null) slots.add(candidate);
                captured = ToolSwapInventorySnapshot.protectedSlots(slots);
            } else {
                noneCaptures++;
                captured = ToolSwapInventorySnapshot.none();
            }
            return new ToolSwapContext(light, !guiOpen, guiOpen, selectedHotbarSlot, captured);
        }

        @Override public boolean isChainKeyPhysicallyDown() { return physicalKeyDown; }
        @Override public Object connectionIdentity() { return handler; }

        @Override
        public ToolSwapClickResult executeMode2(int candidateContainerSlot, int anchorHotbarIndex) {
            if (!finalPreflightPass || guiOpen) {
                return ToolSwapClickResult.NOT_STARTED;
            }
            vanillaCalls++;
            lastContainerSlot = candidateContainerSlot;
            lastButton = anchorHotbarIndex;
            if (throwAfterVanilla) {
                throw new IllegalStateException("synthetic windowClick failure");
            }
            if (emitPacket) {
                adapter.onClickWindowPacket(
                        handler, 0, candidateContainerSlot, anchorHotbarIndex, 2, nextAction++);
            }
            return ToolSwapClickResult.VANILLA_CALLED;
        }

        private void resetCounts() {
            fullCaptures = 0;
            protectedCaptures = 0;
            noneCaptures = 0;
            vanillaCalls = 0;
            finalPreflightPass = true;
            throwAfterVanilla = false;
            untrustedFull = false;
            untrustedProtected = false;
            replaceCandidateBeforeProtectedCapture = false;
            emitPacket = true;
        }
    }
}
