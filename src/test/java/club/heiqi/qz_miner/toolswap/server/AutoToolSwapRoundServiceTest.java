package club.heiqi.qz_miner.toolswap.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapAction;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapIntent;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapResultCode;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundResult;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapRoundState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;

/** v4 wire projection facade：冻结 public surface，但绝不拥有 physical ledger。 */
public class AutoToolSwapRoundServiceTest {

    @Test
    public void pendingActivationIsEndpointBoundIdempotentAndRoundIdsNeverReuse() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();

        AutoToolSwapRoundResult pending = service.beginRound(player, endpoint, 11L, 1L);
        Assert.assertEquals(AutoToolSwapRoundState.PENDING_KEY, pending.roundState());
        Assert.assertEquals(pending, service.beginRound(player, endpoint, 11L, 2L));
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                service.beginRound(player, new Object(), 11L, 2L).outcome());
        AutoToolSwapRoundResult first = service.activatePendingRound(player, endpoint, 3L);
        Assert.assertEquals(1L, first.serverRoundId());
        Assert.assertEquals(first, service.activatePendingRound(player, endpoint, 99L));

        AutoToolSwapRoundResult close = publish(service, player, endpoint,
                control(first.serverRoundId(), 1L, AutoToolSwapAction.CLOSE), 4L);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, close.roundState());
        service.beginRound(player, endpoint, 12L, 5L);
        Assert.assertEquals(2L, service.activatePendingRound(player, endpoint, 6L).serverRoundId());
    }

    @Test
    public void directFreezeAdvancesOnlyProjectionAndNeverReadsInventory() {
        Fixture fixture = new Fixture();
        ExplodingInventory inventory = new ExplodingInventory();
        AutoToolSwapIntent freeze = control(fixture.roundId, 1L, AutoToolSwapAction.FREEZE);

        AutoToolSwapRoundResult result = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                freeze, inventory, 7L);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, result.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, result.roundState());
        Assert.assertEquals(2L, result.nextActionSequence());
        Assert.assertTrue(fixture.service.confirmIntentResultPublication(
                fixture.player, fixture.endpoint, freeze, result));
        Assert.assertFalse(fixture.service.snapshot(fixture.player).hasLedger());
        Assert.assertFalse(fixture.service.snapshot(fixture.player).hasPendingInventorySync());
        Assert.assertEquals(0, inventory.accesses);
    }

    @Test
    public void firstLegacySwapIsRejectedFrozenAndExactRetryIsInventoryFree() {
        Fixture fixture = new Fixture();
        ExplodingInventory inventory = new ExplodingInventory();
        AutoToolSwapIntent swap = mutation(fixture.roundId, 1L, AutoToolSwapAction.SWAP);

        AutoToolSwapRoundResult first = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                swap, inventory, 8L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, first.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, first.roundState());
        Assert.assertEquals(first, fixture.service.handleIntent(fixture.player, fixture.endpoint,
                swap, inventory, 9L));
        Assert.assertTrue(fixture.service.confirmIntentResultPublication(
                fixture.player, fixture.endpoint, swap, first));
        Assert.assertEquals(first, fixture.service.handleIntent(fixture.player, fixture.endpoint,
                swap, inventory, 10L));
        Assert.assertEquals(0, inventory.accesses);
    }

    @Test
    public void allLegacyMutationIntentsAreZeroReadRejected() {
        assertOrdinaryMutationRejected(AutoToolSwapAction.SWAP);
        assertOrdinaryMutationRejected(AutoToolSwapAction.RESTORE);
        assertTakeoverMutationRejected(AutoToolSwapAction.TAKEOVER);
        assertTakeoverMutationRejected(AutoToolSwapAction.DECLINE_TAKEOVER);
    }

    @Test
    public void closeAndAbandonOnlyCloseProjection() {
        for (AutoToolSwapAction action : new AutoToolSwapAction[] {
                AutoToolSwapAction.CLOSE, AutoToolSwapAction.ABANDON }) {
            Fixture fixture = new Fixture();
            ExplodingInventory inventory = new ExplodingInventory();
            AutoToolSwapIntent intent = control(fixture.roundId, 1L, action);
            AutoToolSwapRoundResult result = fixture.service.handleIntent(
                    fixture.player, fixture.endpoint, intent, inventory, 4L);
            Assert.assertEquals(action.name(), AutoToolSwapResultCode.ACCEPTED, result.outcome());
            Assert.assertEquals(action.name(), AutoToolSwapRoundState.FINISHED, result.roundState());
            Assert.assertEquals(action.name(), 0, inventory.accesses);
        }
    }

    @Test
    public void endpointRoundSequenceAndPhaseMismatchesFailClosed() {
        Fixture fixture = new Fixture();
        ExplodingInventory inventory = new ExplodingInventory();
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(
                fixture.player, new Object(), control(fixture.roundId, 1L, AutoToolSwapAction.FREEZE),
                inventory, 1L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(
                fixture.player, fixture.endpoint,
                control(fixture.roundId + 1L, 1L, AutoToolSwapAction.FREEZE), inventory, 1L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(
                fixture.player, fixture.endpoint,
                control(fixture.roundId, 2L, AutoToolSwapAction.FREEZE), inventory, 1L).outcome());
        Assert.assertEquals(0L, fixture.service.observeChainPhase(
                fixture.player, new Object(), fixture.roundId, true, false));
        Assert.assertEquals(1L, fixture.service.observeChainPhase(
                fixture.player, fixture.endpoint, fixture.roundId, true, false));
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN,
                fixture.service.snapshot(fixture.player).roundState());
        Assert.assertEquals(0, inventory.accesses);
    }

    @Test
    public void sourceContainsNoPhysicalMutationOrLedgerOwner() throws Exception {
        String source = new String(Files.readAllBytes(new File(
                "src/main/java/club/heiqi/qz_miner/toolswap/server/AutoToolSwapRoundService.java").toPath()),
                StandardCharsets.UTF_8);
        Assert.assertFalse(source.contains("swapInventorySlotsAtomically("));
        Assert.assertFalse(source.contains("rotateInventorySlotsAtomically("));
        Assert.assertFalse(source.contains("class SwapLedger"));
        Assert.assertFalse(source.contains("readInventorySlot("));
        Assert.assertTrue(source.contains("inventory=unread"));
    }

    @Test
    public void legacyPureValueFingerprintSurfaceRemainsExact() {
        AutoToolSwapStackState[] slots = new AutoToolSwapStackState[AutoToolSwapProtocol.INVENTORY_SLOT_COUNT];
        java.util.Arrays.fill(slots, AutoToolSwapStackState.empty());
        slots[35] = state("mod:last", "a");
        AutoToolSwapRoundService.InventoryFingerprint first =
                AutoToolSwapRoundService.InventoryFingerprint.fromSlots(slots);
        slots[35] = state("mod:last", "b");
        AutoToolSwapRoundService.InventoryFingerprint second =
                AutoToolSwapRoundService.InventoryFingerprint.fromSlots(slots);
        Assert.assertFalse(first.sameInventory(second));
        Assert.assertEquals("a", first.slot(35).roleKey().startsWith("mod:last") ? "a" : "bad");
    }

    private static void assertOrdinaryMutationRejected(AutoToolSwapAction action) {
        Fixture fixture = new Fixture();
        ExplodingInventory inventory = new ExplodingInventory();
        AutoToolSwapRoundResult result = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                mutation(fixture.roundId, 1L, action), inventory, 2L);
        Assert.assertEquals(action.name(), AutoToolSwapResultCode.REJECTED, result.outcome());
        Assert.assertEquals(action.name(), 0, inventory.accesses);
    }

    private static void assertTakeoverMutationRejected(AutoToolSwapAction action) {
        Fixture fixture = new Fixture();
        fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId, true, false);
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 1, 1, 64, 1, 1, 0, 0, state("mod:anchor", "a"), 2L, 8L);
        Assert.assertNotNull(request);
        ExplodingInventory inventory = new ExplodingInventory();
        AutoToolSwapIntent intent = mutation(fixture.roundId, request.takeoverRequestId(), action);
        AutoToolSwapRoundResult result = fixture.service.handleIntent(
                fixture.player, fixture.endpoint, intent, inventory, 3L);
        Assert.assertEquals(action.name(), AutoToolSwapResultCode.REJECTED, result.outcome());
        Assert.assertEquals(action.name(), AutoToolSwapRoundState.FROZEN, result.roundState());
        Assert.assertEquals(action.name(), 0, inventory.accesses);
    }

    private static AutoToolSwapRoundResult publish(AutoToolSwapRoundService service, UUID player,
            Object endpoint, AutoToolSwapIntent intent, long tick) {
        AutoToolSwapRoundResult result = service.handleIntent(player, endpoint, intent, null, tick);
        Assert.assertTrue(service.confirmIntentResultPublication(player, endpoint, intent, result));
        return result;
    }

    private static AutoToolSwapIntent control(long roundId, long sequence, AutoToolSwapAction action) {
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, sequence,
                action, 0, 0, empty, empty);
    }

    private static AutoToolSwapIntent mutation(long roundId, long sequence, AutoToolSwapAction action) {
        AutoToolSwapStackState anchor = state("mod:anchor", "a");
        AutoToolSwapStackState candidate = state("mod:candidate", "b");
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, sequence,
                action, 0, 9, anchor.contentFingerprint(), candidate.contentFingerprint());
    }

    private static AutoToolSwapStackState state(String role, String dynamic) {
        return AutoToolSwapStackState.occupied(role,
                AutoToolSwapContentFingerprint.fromContent(role, dynamic), 20);
    }

    private static final class Fixture {
        private final AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        private final UUID player = UUID.randomUUID();
        private final Object endpoint = new Object();
        private final long roundId;

        private Fixture() {
            service.beginRound(player, endpoint, 1L, 0L);
            roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        }
    }

    private static final class ExplodingInventory implements AutoToolSwapInventoryPort {
        private int accesses;
        private AssertionError accessed() { accesses++; return new AssertionError("inventory must stay unread"); }
        @Override public boolean isPlayerAlive() { throw accessed(); }
        @Override public boolean isCreativeMode() { throw accessed(); }
        @Override public boolean hasPersonalInventoryWindow0() { throw accessed(); }
        @Override public boolean isCursorEmpty() { throw accessed(); }
        @Override public int selectedHotbarSlot() { throw accessed(); }
        @Override public AutoToolSwapStackState readInventorySlot(int slot) { throw accessed(); }
        @Override public void swapInventorySlotsAtomically(int first, int second) { throw accessed(); }
        @Override public void syncInventoryDifference() { throw accessed(); }
    }
}
