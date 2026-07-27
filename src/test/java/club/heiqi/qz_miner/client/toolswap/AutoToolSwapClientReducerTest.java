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
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.ResetEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundPhaseEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.RoundResultEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.TakeoverRequestEvent;
import club.heiqi.qz_miner.client.toolswap.AutoToolSwapClientReducer.TickEvent;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;

/** 新服务端 direct-FREEZE 与旧服务端接替 fallback 的客户端 reducer 合同。 */
public class AutoToolSwapClientReducerTest {

    @Test
    public void newRoundFreezesDirectlyWithoutCandidateCaptureOrPhysicalLedger() {
        AutoToolSwapClientReducer reducer = reducer(true, 11L);
        ToolSwapLightContext light = light(0L, target(1, 0));

        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForKeyState(true, light, 7L));
        Effect begin = only(reducer.reduce(new KeyStateEvent(true,
                context(0L, ToolSwapInventorySnapshot.none(), target(1, 0)), 7L)));
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, begin.type());
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, reducer.state());
        Assert.assertFalse(reducer.hasSwapExpectation());
        submit(reducer, begin);
        acceptRound(reducer, 11L, 71L);

        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForTick(light, true));
        Effect freeze = only(reducer.reduce(new TickEvent(
                context(0L, ToolSwapInventorySnapshot.none(), target(1, 0)), true)));
        Assert.assertEquals(Effect.Type.SEND_INTENT, freeze.type());
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.intent().action());
        Assert.assertFalse(reducer.hasSwapExpectation());
        submit(reducer, freeze);
        settle(reducer, freeze.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FROZEN);

        ChainPhase[] active = {ChainPhase.PLANNING, ChainPhase.RUNNING, ChainPhase.FINISHING};
        for (int index = 0; index < active.length; index++) {
            Assert.assertTrue(reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                    71L, index + 1L, active[index].ordinal(), 4, index + 1L, true)).isEmpty());
            Assert.assertTrue(reducer.reduce(new TickEvent(context(index + 1L,
                    ToolSwapInventorySnapshot.none(), target(1, 0)), true)).isEmpty());
        }
        Assert.assertEquals(AutoToolSwapClientReducer.State.FROZEN, reducer.state());
        Assert.assertFalse(reducer.hasSwapExpectation());
    }

    @Test
    public void releaseClosesProjectionWithoutInventoryCapture() {
        AutoToolSwapClientReducer reducer = frozenRound(true, 12L, 72L);
        ToolSwapLightContext light = light(2L, target(1, 0));

        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForKeyState(false, light, 0L));
        Effect close = only(reducer.reduce(new KeyStateEvent(false,
                context(2L, ToolSwapInventorySnapshot.none(), target(1, 0)))));
        Assert.assertEquals(Effect.Type.SEND_INTENT, close.type());
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.intent().action());
        Assert.assertFalse(reducer.hasSwapExpectation());
        submit(reducer, close);
        settle(reducer, close.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED);

        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, reducer.state());
        Assert.assertEquals(0L, reducer.serverRoundId());
    }

    @Test
    public void roundStartRetransmitsSameNonceAtFixedCadence() {
        AutoToolSwapClientReducer reducer = reducer(true, 13L);
        Effect begin = only(reducer.reduce(new KeyStateEvent(true,
                context(0L, ToolSwapInventorySnapshot.none(), target(1, 0)))));
        submit(reducer, begin);

        Effect retry = null;
        for (int tick = 0; tick <= AutoToolSwapClientReducer.RETRANSMIT_TICKS; tick++) {
            List<Effect> effects = reducer.reduce(new TickEvent(
                    context(tick, ToolSwapInventorySnapshot.none(), target(1, 0)), true));
            if (!effects.isEmpty()) retry = only(effects);
        }

        Assert.assertNotNull(retry);
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, retry.type());
        Assert.assertEquals(begin.clientNonce(), retry.clientNonce());
        Assert.assertTrue(retry.retry());
        Assert.assertFalse(reducer.isOrphaned());
    }

    @Test
    public void legacyTakeoverUsesServerTargetThenRestoresItsTwoSlotLease() {
        AutoToolSwapClientReducer reducer = frozenForLegacyTakeover(true, 21L, 81L);
        submitTakeoverRequest(reducer, 81L, 17L, 4);

        Assert.assertEquals(ToolSwapCapturePlan.FULL_TARGET,
                reducer.capturePlanForTick(light(2L, target(1, 0)), true));
        Effect takeoverEffect = only(reducer.reduce(new TickEvent(
                context(2L, legacySource(), target(42, 7)), true)));
        AutoToolSwapIntent takeover = takeoverEffect.intent();
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER, takeover.action());
        Assert.assertEquals(17L, takeover.takeoverRequestId());
        Assert.assertEquals(7, takeover.candidateSlot());
        Assert.assertFalse("mutation 可见前客户端不得伪造 physical ledger",
                reducer.hasSwapExpectation());
        submit(reducer, takeoverEffect);
        settle(reducer, takeover, AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.FROZEN);

        Assert.assertEquals(ToolSwapCapturePlan.FULL,
                reducer.capturePlanForTick(light(3L, target(42, 7)), true));
        Effect takeoverVisible = only(reducer.reduce(new TickEvent(
                context(3L, legacyTarget(), target(42, 7)), true)));
        Assert.assertEquals(Effect.Type.PREVIEW_INVALIDATE, takeoverVisible.type());
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER, takeoverVisible.action());
        Assert.assertTrue(reducer.hasSwapExpectation());

        Assert.assertEquals(ToolSwapCapturePlan.PROTECTED,
                reducer.capturePlanForKeyState(false, light(4L, target(42, 7)), 0L));
        Effect restoreCapture = only(reducer.reduce(new KeyStateEvent(false,
                context(4L, legacyTarget(), target(42, 7)))));
        Assert.assertEquals(Effect.Type.CAPTURE, restoreCapture.type());
        Effect restoreEffect = only(reducer.reduce(new EffectResultEvent(restoreCapture, true,
                context(4L, legacyTarget(), target(42, 7)))));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restoreEffect.intent().action());
        submit(reducer, restoreEffect);
        settle(reducer, restoreEffect.intent(), AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.CLOSING);

        List<Effect> restored = reducer.reduce(new TickEvent(
                context(5L, legacySource(), target(42, 7)), false));
        Assert.assertEquals(AutoToolSwapAction.RESTORE,
                effectOfType(restored, Effect.Type.PREVIEW_INVALIDATE).action());
        Effect close = effectOfType(restored, Effect.Type.SEND_INTENT);
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.intent().action());
        Assert.assertFalse(reducer.hasSwapExpectation());
    }

    @Test
    public void consecutiveLegacyTakeoversRollThreeSlotLeaseBeforeFinalRestore() {
        AutoToolSwapClientReducer reducer = frozenForLegacyTakeover(true, 27L, 87L);
        submitTakeoverRequest(reducer, 87L, 21L, 4);
        Effect firstEffect = only(reducer.reduce(new TickEvent(
                context(2L, legacySource(), target(42, 7)), true)));
        submit(reducer, firstEffect);
        settle(reducer, firstEffect.intent(), AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.FROZEN);
        reducer.reduce(new TickEvent(context(3L, legacyTarget(), target(42, 7)), true));
        Assert.assertEquals(7, reducer.protectedCandidateSlot());

        submitTakeoverRequest(reducer, 87L, 22L, 4);
        Assert.assertEquals(ToolSwapCapturePlan.FULL_TARGET,
                reducer.capturePlanForTick(light(4L, target(43, 8)), true));
        Effect secondEffect = only(reducer.reduce(new TickEvent(
                context(4L, legacySecondSource(), target(43, 8)), true)));
        Assert.assertEquals(AutoToolSwapAction.TAKEOVER, secondEffect.intent().action());
        Assert.assertEquals(8, secondEffect.intent().candidateSlot());
        submit(reducer, secondEffect);
        settle(reducer, secondEffect.intent(), AutoToolSwapResultCode.APPLIED,
                AutoToolSwapRoundState.FROZEN);

        Effect visible = only(reducer.reduce(new TickEvent(
                context(5L, legacySecondTarget(), target(43, 8)), true)));
        Assert.assertEquals(Effect.Type.PREVIEW_INVALIDATE, visible.type());
        Assert.assertEquals(8, reducer.protectedCandidateSlot());

        Effect restoreCapture = only(reducer.reduce(new KeyStateEvent(false,
                context(6L, legacySecondTarget(), target(43, 8)))));
        Effect restore = only(reducer.reduce(new EffectResultEvent(restoreCapture, true,
                context(6L, legacySecondTarget(), target(43, 8)))));
        Assert.assertEquals(AutoToolSwapAction.RESTORE, restore.intent().action());
        Assert.assertEquals(8, restore.intent().candidateSlot());
    }

    @Test
    public void disabledLegacyTakeoverDeclinesWithoutFullInventoryScan() {
        AutoToolSwapClientReducer reducer = frozenForLegacyTakeover(false, 22L, 82L);
        submitTakeoverRequest(reducer, 82L, 18L, 4);

        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForTick(light(2L, target(42, 7)), true));
        Effect decline = only(reducer.reduce(new TickEvent(
                context(2L, ToolSwapInventorySnapshot.none(), target(1, 0)), true)));
        Assert.assertEquals(AutoToolSwapAction.DECLINE_TAKEOVER, decline.intent().action());
        Assert.assertEquals(18L, decline.intent().takeoverRequestId());
        Assert.assertFalse(reducer.hasSwapExpectation());
    }

    @Test
    public void legacyTakeoverSyncFailureRetriesExactIntentWithoutInventoryObservation() {
        AutoToolSwapClientReducer reducer = frozenForLegacyTakeover(true, 23L, 83L);
        submitTakeoverRequest(reducer, 83L, 19L, 4);
        Effect takeoverEffect = only(reducer.reduce(new TickEvent(
                context(2L, legacySource(), target(42, 7)), true)));
        AutoToolSwapIntent takeover = takeoverEffect.intent();
        submit(reducer, takeoverEffect);

        settle(reducer, takeover, AutoToolSwapResultCode.SYNC_FAILED,
                AutoToolSwapRoundState.FROZEN);
        Assert.assertTrue(reducer.isPublicationRetryPending());
        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForTick(light(3L, target(42, 7)), true));
        Effect retry = only(reducer.reduce(new TickEvent(
                context(3L, ToolSwapInventorySnapshot.none(), target(42, 7)), true)));
        Assert.assertSame(takeover, retry.intent());
        Assert.assertTrue(retry.retry());
        Assert.assertFalse(reducer.hasSwapExpectation());
    }

    @Test
    public void staleLegacyRequestIsIgnoredBeforeCandidateCapture() {
        AutoToolSwapClientReducer reducer = frozenForLegacyTakeover(true, 24L, 84L);

        Assert.assertTrue(reducer.reduce(new TakeoverRequestEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                84L, 20L, 5, 10, 64, 20, 42, 7, 2L, 12L, true)).isEmpty());
        Assert.assertEquals(0L, reducer.latestTakeoverRequestId());
        Assert.assertEquals(ToolSwapCapturePlan.NONE,
                reducer.capturePlanForTick(light(2L, target(42, 7)), true));
    }

    @Test
    public void naturalIdleClosesThenDefersFreshRoundToNextTick() {
        AutoToolSwapClientReducer reducer = frozenRound(true, 25L, 85L);
        Assert.assertTrue(reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                85L, 1L, ChainPhase.IDLE.ordinal(), 4, 2L, true)).isEmpty());

        Effect close = only(reducer.reduce(new TickEvent(
                context(2L, ToolSwapInventorySnapshot.none(), target(1, 0)), true)));
        Assert.assertEquals(AutoToolSwapAction.CLOSE, close.intent().action());
        submit(reducer, close);
        settle(reducer, close.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FINISHED);
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, reducer.state());

        Effect nextRound = only(reducer.reduce(new TickEvent(
                context(3L, ToolSwapInventorySnapshot.none(), target(1, 0)), true)));
        Assert.assertEquals(Effect.Type.BEGIN_ROUND, nextRound.type());
        Assert.assertTrue(nextRound.clientNonce() > 25L);
    }

    @Test
    public void lifecycleResetClearsProjectionWithoutRecoveryIntent() {
        AutoToolSwapClientReducer reducer = frozenRound(true, 26L, 86L);

        Assert.assertTrue(reducer.reduce(new ResetEvent()).isEmpty());
        Assert.assertEquals(AutoToolSwapClientReducer.State.IDLE, reducer.state());
        Assert.assertEquals(0L, reducer.serverRoundId());
        Assert.assertFalse(reducer.isKeyDown());
        Assert.assertFalse(reducer.hasSwapExpectation());
    }

    private static AutoToolSwapClientReducer frozenRound(boolean takeoverEnabled,
            long nonce, long roundId) {
        AutoToolSwapClientReducer reducer = reducer(takeoverEnabled, nonce, nonce + 1L);
        Effect begin = only(reducer.reduce(new KeyStateEvent(true,
                context(0L, ToolSwapInventorySnapshot.none(), target(1, 0)))));
        submit(reducer, begin);
        acceptRound(reducer, nonce, roundId);
        Effect freeze = only(reducer.reduce(new TickEvent(
                context(0L, ToolSwapInventorySnapshot.none(), target(1, 0)), true)));
        Assert.assertEquals(AutoToolSwapAction.FREEZE, freeze.intent().action());
        submit(reducer, freeze);
        settle(reducer, freeze.intent(), AutoToolSwapResultCode.ACCEPTED,
                AutoToolSwapRoundState.FROZEN);
        return reducer;
    }

    private static AutoToolSwapClientReducer frozenForLegacyTakeover(boolean takeoverEnabled,
            long nonce, long roundId) {
        AutoToolSwapClientReducer reducer = frozenRound(takeoverEnabled, nonce, roundId);
        Assert.assertTrue(reducer.reduce(new RoundPhaseEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                roundId, 1L, ChainPhase.RUNNING.ordinal(), 4, 1L, true)).isEmpty());
        return reducer;
    }

    private static AutoToolSwapClientReducer reducer(boolean takeoverEnabled, final long... nonces) {
        return new AutoToolSwapClientReducer(true, takeoverEnabled, Collections.emptyList(),
                new AutoToolSwapClientReducer.NonceAllocator() {
                    private int index;

                    @Override
                    public long allocate() {
                        return index < nonces.length ? nonces[index++] : 0L;
                    }
                }, new AutoToolSwapClientReducer.DiagnosticSink() {
                    @Override
                    public void log(String message) {
                    }
                });
    }

    private static void submitTakeoverRequest(AutoToolSwapClientReducer reducer,
            long roundId, long requestId, int generation) {
        Assert.assertTrue(reducer.reduce(new TakeoverRequestEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                roundId, requestId, generation, 10, 64, 20, 42, 7,
                2L, 12L, true)).isEmpty());
    }

    private static void acceptRound(AutoToolSwapClientReducer reducer,
            long nonce, long roundId) {
        Assert.assertTrue(reducer.reduce(new RoundResultEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                nonce, roundId, AutoToolSwapResultCode.ACCEPTED.wireCode(),
                AutoToolSwapRoundState.OPEN.wireCode(), 1L, 0L, true)).isEmpty());
    }

    private static void settle(AutoToolSwapClientReducer reducer, AutoToolSwapIntent intent,
            AutoToolSwapResultCode result, AutoToolSwapRoundState state) {
        long nextActionSequence = intent.usesTakeoverRequestId()
                ? reducer.nextActionSequence() : intent.actionSequence() + 1L;
        reducer.reduce(new ActionResultEvent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                intent.serverRoundId(), intent.actionSequence(), intent.action().wireCode(),
                result.wireCode(), state.wireCode(), intent.anchorSlot(), intent.candidateSlot(),
                nextActionSequence, 1L, true));
    }

    private static void submit(AutoToolSwapClientReducer reducer, Effect effect) {
        reducer.reduce(new EffectResultEvent(effect, true, null));
    }

    private static Effect only(List<Effect> effects) {
        Assert.assertEquals("effects=" + effects.size(), 1, effects.size());
        return effects.get(0);
    }

    private static Effect effectOfType(List<Effect> effects, Effect.Type type) {
        Effect matched = null;
        for (Effect effect : effects) {
            if (effect.type() == type) {
                Assert.assertNull("duplicate effect type=" + type, matched);
                matched = effect;
            }
        }
        Assert.assertNotNull("missing effect type=" + type, matched);
        return matched;
    }

    private static ToolSwapLightContext light(long tick, ToolSwapTargetIdentity target) {
        return new ToolSwapLightContext(tick, true, false, false, true, 0, target);
    }

    private static ToolSwapContext context(long tick, ToolSwapInventorySnapshot inventory,
            ToolSwapTargetIdentity target) {
        return new ToolSwapContext(tick, true, false, false, true, true, 0, inventory, target);
    }

    private static ToolSwapTargetIdentity target(int blockId, int metadata) {
        return ToolSwapTargetIdentity.present(blockId, metadata);
    }

    private static ToolSwapInventorySnapshot legacySource() {
        return inventory(new SlotSnapshot(0, "hand", "old"),
                new SlotSnapshot(7, "drill", "fresh"), tool(7, "drill", true));
    }

    private static ToolSwapInventorySnapshot legacyTarget() {
        return inventory(new SlotSnapshot(0, "drill", "used"),
                new SlotSnapshot(7, "hand", "old"), tool(0, "drill", true));
    }

    private static ToolSwapInventorySnapshot legacySecondSource() {
        return inventory(new SlotSnapshot(0, "drill", "used"),
                new SlotSnapshot(7, "hand", "old"),
                new SlotSnapshot(8, "saw", "fresh"), tool(8, "saw", true));
    }

    private static ToolSwapInventorySnapshot legacySecondTarget() {
        return inventory(new SlotSnapshot(0, "saw", "used"),
                new SlotSnapshot(7, "drill", "used"),
                new SlotSnapshot(8, "hand", "old"), tool(0, "saw", true));
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
}
