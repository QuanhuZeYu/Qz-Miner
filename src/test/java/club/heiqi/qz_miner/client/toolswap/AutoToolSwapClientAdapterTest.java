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
        Assert.assertEquals(AutoToolSwapAction.RESTORE, transport.intents.get(1).action());
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
        Assert.assertEquals(AutoToolSwapAction.FREEZE, transport.intents.get(0).action());
    }

    private void acceptRound() {
        adapter.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION, transport.rounds.get(0).longValue(), 9L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(), 1L, 0L, true);
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
        @Override public ToolSwapLightContext captureLightContext(long tick, boolean active) {
            return new ToolSwapLightContext(tick, true, false, false, active, 0);
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
            return new ToolSwapContext(light, true, false, 0, captured);
        }
        @Override public boolean isChainKeyPhysicallyDown() { return physicalKeyDown; }
    }
}
