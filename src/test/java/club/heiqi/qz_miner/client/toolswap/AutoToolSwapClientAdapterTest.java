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

/** direct-FREEZE runtime effect 与旧服务端接替 fallback 的 adapter 合同。 */
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
    public void risingEdgeSendsRoundThenDirectFreezeWithoutFullInventoryCapture() {
        game.physicalKeyDown = true;
        adapter.onChainKeyState(true);

        Assert.assertEquals(1, transport.rounds.size());
        Assert.assertTrue("RoundResult callback 前不得发送 ordinary intent", transport.intents.isEmpty());
        Assert.assertEquals(ToolSwapCapturePlan.NONE, game.capturePlans.get(0));
        Assert.assertEquals(0, game.fullCaptureCount);
        acceptRound();
        Assert.assertTrue("S2C callback 内不得发送 C2S", transport.intents.isEmpty());

        adapter.onClientTick();
        Assert.assertEquals(1, transport.intents.size());
        AutoToolSwapIntent freeze = transport.intents.get(0);
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.action());
        Assert.assertEquals(0, game.fullCaptureCount);
        Assert.assertFalse(adapter.reducerForTests().hasSwapExpectation());
        settle(freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);

        ChainPhase[] active = {ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING};
        for (int index = 0; index < active.length; index++) {
            adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, index + 1L,
                    active[index].ordinal(), 4, index + 1L, true);
            adapter.onClientTick();
        }
        Assert.assertEquals("活跃 phase 不得发送第二个 FREEZE", 1, transport.intents.size());
        Assert.assertEquals(0, game.fullCaptureCount);
    }

    @Test
    public void releaseSendsProjectionCloseWithoutInventoryCapture() {
        game.physicalKeyDown = true;
        openAndFreeze();

        game.physicalKeyDown = false;
        adapter.onChainKeyState(false);
        Assert.assertEquals(2, transport.intents.size());
        AutoToolSwapIntent close = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.action());
        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                game.capturePlans.get(game.capturePlans.size() - 1));
        Assert.assertEquals(0, game.fullCaptureCount);
        Assert.assertEquals(0, game.protectedCaptureCount);

        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, adapter.reducerForTests().state());
        Assert.assertEquals(0L, adapter.reducerForTests().serverRoundId());
    }

    @Test
    public void legacyRequestWaitsForNextTickUsesServerTargetAndRestoresVisibleLease() {
        final List<AutoToolSwapAction> notices = new ArrayList<AutoToolSwapAction>();
        adapter = new AutoToolSwapClientAdapter(true, true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientAdapter.PreviewInvalidationListener() {
                    @Override
                    public void onPreviewInvalidated(long cycleGeneration, long serverRoundId,
                            long actionSequence, AutoToolSwapAction action) {
                        notices.add(action);
                    }
                });
        game.physicalKeyDown = true;
        openAndFreeze();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.RUNNING.ordinal(), 4, 2L, true);
        game.inventory = legacySource();

        adapter.onTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 17L, 4,
                10, 64, 20, 42, 7, 3L, 12L, true);
        Assert.assertEquals("S2C callback 不得发送 legacy response", 1, transport.intents.size());
        adapter.onClientTick();

        Assert.assertEquals(2, transport.intents.size());
        AutoToolSwapIntent takeover = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER, takeover.action());
        Assert.assertEquals(17L, takeover.takeoverRequestId());
        Assert.assertEquals(7, takeover.candidateSlot());
        Assert.assertEquals(42, game.lastTargetBlockId);
        Assert.assertEquals(7, game.lastTargetMetadata);
        Assert.assertEquals(1, game.fullTargetCaptureCount);
        Assert.assertFalse(adapter.reducerForTests().hasSwapExpectation());

        settle(takeover, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.FROZEN);
        game.inventory = legacyTarget();
        adapter.onClientTick();
        Assert.assertEquals(Collections.singletonList(AutoToolSwapAction.TAKEOVER), notices);
        Assert.assertTrue(adapter.reducerForTests().hasSwapExpectation());

        game.physicalKeyDown = false;
        adapter.onChainKeyState(false);
        AutoToolSwapIntent restore = transport.intents.get(2);
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.action());
        Assert.assertEquals("release fact 与 RESTORE effect 各做一次受保护槽采样",
                2, game.protectedCaptureCount);
        settle(restore, AutoToolSwapResultCode.APPLIED, AutoToolSwapRoundState.CLOSING);
        game.inventory = legacySource();
        adapter.onClientTick();

        Assert.assertEquals(Arrays.asList(AutoToolSwapAction.TAKEOVER,
                AutoToolSwapAction.RESTORE), notices);
        Assert.assertEquals(AutoToolSwapAction.CLOSE,
                transport.intents.get(transport.intents.size() - 1).action());
        Assert.assertFalse(adapter.reducerForTests().hasSwapExpectation());
    }

    @Test
    public void disabledLegacyTakeoverDeclinesWithoutInventoryScan() {
        adapter = new AutoToolSwapClientAdapter(true, false, Collections.emptyList(), game, transport);
        game.physicalKeyDown = true;
        openAndFreeze();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.RUNNING.ordinal(), 4, 2L, true);

        adapter.onTakeoverRequest(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 18L, 4,
                10, 64, 20, 42, 7, 3L, 12L, true);
        Assert.assertEquals(1, transport.intents.size());
        adapter.onClientTick();

        Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER, transport.intents.get(1).action());
        Assert.assertEquals(0, game.fullCaptureCount);
        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                game.capturePlans.get(game.capturePlans.size() - 1));
    }

    @Test
    public void configCloseWaitsForInFlightFreezeAndNeverStartsCandidateScan() {
        game.physicalKeyDown = true;
        adapter.onChainKeyState(true);
        acceptRound();
        adapter.onClientTick();
        AutoToolSwapIntent freeze = transport.intents.get(0);

        adapter.onConfigChanged(false, true, Collections.emptyList());
        Assert.assertEquals("FREEZE 未结算时不得覆盖 in-flight", 1, transport.intents.size());
        settle(freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);
        Assert.assertEquals("ActionResult callback 内不得发送 CLOSE", 1, transport.intents.size());
        adapter.onClientTick();

        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(1).action());
        Assert.assertEquals(0, game.fullCaptureCount);
    }

    @Test
    public void roundStartRetransmitsSameNonceThenUsesHardDeadline() {
        game.physicalKeyDown = true;
        adapter.onChainKeyState(true);
        long nonce = transport.rounds.get(0).longValue();

        for (int tick = 0; tick <= AutoToolSwapClientReducer.RETRANSMIT_TICKS; tick++) {
            adapter.onClientTick();
        }
        Assert.assertEquals(2, transport.rounds.size());
        Assert.assertEquals(nonce, transport.rounds.get(1).longValue());

        while (!adapter.reducerForTests().isOrphaned()
                && adapter.reducerForTests().clientTick() <= AutoToolSwapClientReducer.TRANSMISSION_DEADLINE_TICKS) {
            adapter.onClientTick();
        }
        Assert.assertTrue(adapter.reducerForTests().isOrphaned());
    }

    @Test
    public void naturalIdleRearmsOnlyOnFollowingTickWhilePhysicalKeyRemainsDown() {
        game.physicalKeyDown = true;
        openAndFreeze();
        long firstNonce = transport.rounds.get(0).longValue();
        adapter.onRoundPhase(AutoToolSwapProtocol.PROTOCOL_VERSION, 9L, 1L,
                ChainPhase.IDLE.ordinal(), 4, 2L, true);
        Assert.assertEquals(1, transport.intents.size());

        Assert.assertFalse(adapter.onClientTick());
        AutoToolSwapIntent close = transport.intents.get(1);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.action());
        settle(close, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals("S2C callback 内不得创建新 round", 1, transport.rounds.size());

        Assert.assertTrue(adapter.onClientTick());
        Assert.assertEquals(2, transport.rounds.size());
        Assert.assertTrue(transport.rounds.get(1).longValue() > firstNonce);
    }

    @Test
    public void failedReleaseCaptureStillClosesProjectionAndLogsOnce() {
        final List<String> diagnostics = new ArrayList<String>();
        adapter = new AutoToolSwapClientAdapter(true, true, Collections.emptyList(), game, transport,
                new AutoToolSwapClientReducer.DiagnosticSink() {
                    @Override
                    public void log(String message) {
                        diagnostics.add(message);
                    }
                });
        game.physicalKeyDown = true;
        openAndFreeze();
        game.failLightCapture = true;
        game.physicalKeyDown = false;

        adapter.onChainKeyState(false);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, transport.intents.get(1).action());
        Assert.assertEquals(1, Collections.frequency(diagnostics,
                AutoToolSwapClientAdapter.RELEASE_CAPTURE_FAILED_MARKER));
        adapter.onChainKeyState(false);
        Assert.assertEquals(1, Collections.frequency(diagnostics,
                AutoToolSwapClientAdapter.RELEASE_CAPTURE_FAILED_MARKER));
    }

    @Test
    public void transportFailureOrLifecycleResetNeverSendsRecoveryPackets() {
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
    public void disabledCycleDoesNotSendRoundStart() {
        adapter.onConfigChanged(false, Collections.emptyList());
        adapter.onChainKeyState(true);

        Assert.assertTrue(transport.rounds.isEmpty());
        Assert.assertTrue(transport.intents.isEmpty());
        Assert.assertEquals(AutoToolSwapClientReducer.State.WAIT_RELEASE,
                adapter.reducerForTests().state());
    }

    private AutoToolSwapIntent openAndFreeze() {
        adapter.onChainKeyState(true);
        acceptRound();
        Assert.assertTrue("RoundResult callback 内不得发送 FREEZE", transport.intents.isEmpty());
        adapter.onClientTick();
        AutoToolSwapIntent freeze = transport.intents.get(0);
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.action());
        settle(freeze, AutoToolSwapResultCode.ACCEPTED, AutoToolSwapRoundState.FROZEN);
        return freeze;
    }

    private void acceptRound() {
        adapter.onRoundResult(AutoToolSwapProtocol.PROTOCOL_VERSION,
                transport.rounds.get(transport.rounds.size() - 1).longValue(), 9L,
                AutoToolSwapResultCode.ACCEPTED.wireCode(), AutoToolSwapRoundState.OPEN.wireCode(),
                1L, 0L, true);
    }

    private void settle(AutoToolSwapIntent intent, AutoToolSwapResultCode result,
            AutoToolSwapRoundState state) {
        long nextActionSequence = intent.usesTakeoverRequestId()
                ? adapter.reducerForTests().nextActionSequence() : intent.actionSequence() + 1L;
        adapter.onActionResult(AutoToolSwapProtocol.PROTOCOL_VERSION,
                intent.serverRoundId(), intent.actionSequence(), intent.action().wireCode(),
                result.wireCode(), state.wireCode(), intent.anchorSlot(), intent.candidateSlot(),
                nextActionSequence, 1L, true);
    }

    private static ToolSwapInventorySnapshot legacySource() {
        return inventory(new SlotSnapshot(0, "hand", "old"),
                new SlotSnapshot(7, "drill", "fresh"), tool(7, "drill", true));
    }

    private static ToolSwapInventorySnapshot legacyTarget() {
        return inventory(new SlotSnapshot(0, "drill", "used"),
                new SlotSnapshot(7, "hand", "old"), tool(0, "drill", true));
    }

    private static ToolCandidate tool(int slot, String name, boolean usable) {
        return new ToolCandidate(slot, "test:" + name, 0, Arrays.asList("toolPickaxe"),
                usable, usable, 100);
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

        @Override
        public boolean sendRoundStart(long clientNonce) {
            rounds.add(Long.valueOf(clientNonce));
            return accept;
        }

        @Override
        public boolean sendIntent(AutoToolSwapIntent intent) {
            intents.add(intent);
            return accept;
        }
    }

    private static final class FakeGame implements AutoToolSwapClientAdapter.GameFacade {
        private ToolSwapInventorySnapshot inventory = legacySource();
        private boolean physicalKeyDown;
        private boolean breakCapable = true;
        private boolean creative;
        private boolean guiOpen;
        private boolean inventoryTransactionSafe = true;
        private boolean failLightCapture;
        private int lastTargetBlockId;
        private int lastTargetMetadata;
        private ToolSwapTargetIdentity targetIdentity = ToolSwapTargetIdentity.present(1, 0);
        private final List<ToolSwapCapturePlan> capturePlans = new ArrayList<ToolSwapCapturePlan>();
        private int fullCaptureCount;
        private int fullTargetCaptureCount;
        private int protectedCaptureCount;

        @Override
        public ToolSwapLightContext captureLightContext(long tick, boolean active) {
            if (failLightCapture) return null;
            return new ToolSwapLightContext(tick, breakCapable, creative, guiOpen,
                    active, 0, targetIdentity);
        }

        @Override
        public ToolSwapContext captureContext(ToolSwapLightContext light, ToolSwapCapturePlan plan,
                int anchor, int candidate, int targetBlockId, int targetBlockMetadata) {
            capturePlans.add(plan);
            if (plan == ToolSwapCapturePlan.FULL || plan == ToolSwapCapturePlan.FULL_TARGET) {
                fullCaptureCount++;
            }
            if (plan == ToolSwapCapturePlan.FULL_TARGET) fullTargetCaptureCount++;
            if (plan == ToolSwapCapturePlan.PROTECTED) protectedCaptureCount++;
            lastTargetBlockId = targetBlockId;
            lastTargetMetadata = targetBlockMetadata;
            ToolSwapInventorySnapshot captured = inventory;
            if (plan == ToolSwapCapturePlan.PROTECTED) {
                ArrayList<SlotSnapshot> slots = new ArrayList<SlotSnapshot>();
                if (inventory.slot(anchor) != null) slots.add(inventory.slot(anchor));
                if (candidate != anchor && inventory.slot(candidate) != null) {
                    slots.add(inventory.slot(candidate));
                }
                captured = ToolSwapInventorySnapshot.protectedSlots(slots);
            } else if (plan == ToolSwapCapturePlan.NONE) {
                captured = ToolSwapInventorySnapshot.none();
            }
            return new ToolSwapContext(light, inventoryTransactionSafe && !light.guiOpen,
                    light.guiOpen, 0, captured);
        }

        @Override
        public boolean isChainKeyPhysicallyDown() {
            return physicalKeyDown;
        }
    }
}
