package club.heiqi.qz_miner.client.toolswap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.statemachine.ChainPhase;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ActionResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.Effect;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.EffectResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.KeyStateEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.LocalBlockDestroyedEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ResetEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundPhaseEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.TickEvent;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
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
        return new AutoToolSwapClientReducer(true, Collections.emptyList(),
                new AutoToolSwapClientReducer.NonceAllocator() {
                    @Override public long allocate() { return nonce; }
                }, new AutoToolSwapClientReducer.DiagnosticSink() {
                    @Override public void log(String message) { diagnostics.add(message); }
                });
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

    private static ToolSwapContext context(long tick, ToolSwapInventorySnapshot inventory) {
        return context(tick, false, inventory);
    }

    private static ToolSwapContext context(long tick, boolean gui, ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, gui, !gui, true, 0, inventory);
    }

    private static ToolSwapContext context(long tick, int slot, ToolSwapInventorySnapshot inventory) {
        return new ToolSwapContext(tick, true, false, false, true, true, slot, inventory);
    }

    private static ToolSwapInventorySnapshot restored() {
        return inventory(new SlotSnapshot(0, "hand", "old"), new SlotSnapshot(5, "pick", "fresh"),
                tool(0, "hand", false), tool(5, "pick", true));
    }

    private static ToolSwapInventorySnapshot swapped() {
        return inventory(new SlotSnapshot(0, "pick", "used"), new SlotSnapshot(5, "hand", "old"),
                tool(0, "pick", true), tool(5, "hand", false));
    }

    private static ToolSwapInventorySnapshot swappedAnchorChanged() {
        return inventory(new SlotSnapshot(0, "pick", "energy=20;damage=7"),
                new SlotSnapshot(5, "hand", "count=3;nbt=merged"),
                tool(0, "pick", true), tool(5, "hand", false));
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
