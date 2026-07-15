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

/** adapter 的原版事务、phase 归因与 lifecycle 失效合同。 */
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

        Assert.assertEquals(1, game.clicks);
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
        Assert.assertEquals(2, game.clicks);
        Assert.assertEquals("restore uses the same candidate slot", 41, game.lastContainerSlot);
        Assert.assertEquals(0, game.selectedHotbarSlot);
    }

    @Test
    public void onlyUpdatedAttributedActivePhaseFreezesAndIdleEndsRound() {
        game.inventory = noCandidate();
        adapter.onChainKeyState(true);
        phase.set(ChainPhase.RUNNING, 5);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.PREPARING, adapter.controllerForTests().state());

        phase.set(ChainPhase.PLANNING, 6);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.FROZEN, adapter.controllerForTests().state());

        phase.set(ChainPhase.ARMED, 7);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.FROZEN, adapter.controllerForTests().state());
        phase.set(ChainPhase.IDLE, 7);
        adapter.onClientTick();
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, adapter.controllerForTests().state());
    }

    @Test
    public void lifecycleResetInvalidatesOldGenerationAndDoesNotSendRecoveryClick() {
        game.inventory = restored();
        adapter.onChainKeyState(true);
        long oldGeneration = adapter.controllerForTests().generation();
        Assert.assertEquals(1, game.clicks);

        adapter.resetForLifecycle();
        adapter.onTransactionAck(ClientConnectionLifecycle.capture(), 0, 70, true);
        adapter.controllerForTests().onLocalBlockDestroyed(oldGeneration);
        adapter.onClientTick();

        Assert.assertEquals(AutoToolSwapState.IDLE, adapter.controllerForTests().state());
        Assert.assertEquals(1, game.clicks);
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
        Assert.assertEquals("same tick is not stable", ToolSwapTransactionState.SYNC_ISOLATION,
                adapter.controllerForTests().transactionState());
        adapter.onClientTick();
        Assert.assertEquals(ToolSwapTransactionState.IDLE, adapter.controllerForTests().transactionState());
        Assert.assertEquals(AutoToolSwapState.WAIT_RELEASE, adapter.controllerForTests().state());
        Assert.assertEquals("restored layout must not click again", 1, game.clicks);
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
        private int clicks;
        private int lastContainerSlot;
        private int lastButton;
        private int nextAction = 70;
        private FakeGame(Object handler) { this.handler = handler; }
        @Override public ToolSwapContext captureContext(long tick, boolean chainActive) {
            return new ToolSwapContext(tick, true, false, false, true, chainActive,
                    selectedHotbarSlot, inventory);
        }
        @Override public Object connectionIdentity() { return handler; }
        @Override public void executeMode2(int candidateContainerSlot, int anchorHotbarIndex) {
            clicks++;
            lastContainerSlot = candidateContainerSlot;
            lastButton = anchorHotbarIndex;
            adapter.onClickWindowPacket(handler, 0, candidateContainerSlot, anchorHotbarIndex, 2, nextAction++);
        }
    }
}
