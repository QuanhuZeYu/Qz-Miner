package club.heiqi.qz_miner.toolswap.server;

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

/** 服务端工具换位 round 的纯 JVM 事务合同。 */
public class AutoToolSwapRoundServiceTest {

    private static final AutoToolSwapStackState ORIGINAL = stack("mod:pickaxe", "original", 90);
    private static final AutoToolSwapStackState CANDIDATE = stack("mod:drill", "candidate", 40);

    @Test
    public void pendingActivationIsEndpointBoundIdempotentAndRoundIdsDoNotReuse() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService();
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();

        AutoToolSwapRoundResult pending = service.beginRound(player, endpoint, 11L, 1L);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, pending.outcome());
        Assert.assertEquals(0L, pending.serverRoundId());
        Assert.assertEquals(AutoToolSwapRoundState.PENDING_KEY, pending.roundState());
        Assert.assertEquals(pending, service.beginRound(player, endpoint, 11L, 1L));
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                service.beginRound(player, endpoint, 12L, 2L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                service.activatePendingRound(player, new Object(), 3L).outcome());

        AutoToolSwapRoundResult active = service.activatePendingRound(player, endpoint, 4L);
        Assert.assertEquals(1L, active.serverRoundId());
        Assert.assertEquals(active, service.activatePendingRound(player, endpoint, 99L));
        Assert.assertEquals(1L, service.currentRoundId(player, endpoint));

        AutoToolSwapRoundResult close = service.handleIntent(player, endpoint,
                intent(active.serverRoundId(), 1L, AutoToolSwapAction.CLOSE, 0, 9, ORIGINAL, CANDIDATE), null, 5L);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, close.roundState());
        service.beginRound(player, endpoint, 22L, 6L);
        AutoToolSwapRoundResult second = service.activatePendingRound(player, endpoint, 7L);
        Assert.assertEquals(2L, second.serverRoundId());
    }

    @Test
    public void counterAndActionSequenceOverflowFailClosed() {
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        AutoToolSwapRoundService roundOverflow = new AutoToolSwapRoundService(Long.MAX_VALUE);
        roundOverflow.beginRound(player, endpoint, 1L, 0L);
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED,
                roundOverflow.activatePendingRound(player, endpoint, 1L).roundState());
        Assert.assertEquals(0L, roundOverflow.currentRoundId(player));

        AutoToolSwapRoundService sequenceOverflow = new AutoToolSwapRoundService(0L, Long.MAX_VALUE);
        sequenceOverflow.beginRound(player, endpoint, 2L, 2L);
        long roundId = sequenceOverflow.activatePendingRound(player, endpoint, 3L).serverRoundId();
        FakeInventory inventory = inventory();
        AutoToolSwapIntent swap = intent(roundId, Long.MAX_VALUE, AutoToolSwapAction.SWAP, 0, 9,
                ORIGINAL, CANDIDATE);
        AutoToolSwapRoundResult result = sequenceOverflow.handleIntent(player, endpoint, swap, inventory, 4L);
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED, result.roundState());
        Assert.assertEquals(0, inventory.swapCount);
        Assert.assertEquals(result, sequenceOverflow.handleIntent(player, endpoint, swap, inventory, 5L));
    }

    @Test
    public void actionSequenceCachesOnlyLastIdenticalRequestAndRejectsGaps() {
        Fixture fixture = fixture();
        AutoToolSwapIntent rejectedSwap = intent(fixture.roundId, 1L, AutoToolSwapAction.SWAP, 0, 9,
                ORIGINAL, stack("mod:drill", "stale", 40));
        AutoToolSwapRoundResult rejected = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                rejectedSwap, fixture.inventory, 10L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, rejected.outcome());
        Assert.assertEquals(2L, rejected.nextActionSequence());
        Assert.assertEquals(rejected, fixture.service.handleIntent(fixture.player, fixture.endpoint,
                rejectedSwap, fixture.inventory, 99L));

        AutoToolSwapIntent changedPayload = intent(fixture.roundId, 1L, AutoToolSwapAction.FREEZE, 0, 9,
                ORIGINAL, CANDIDATE);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, changedPayload, fixture.inventory, 11L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, intent(fixture.roundId, 3L, AutoToolSwapAction.FREEZE, 0, 9,
                        ORIGINAL, CANDIDATE), fixture.inventory, 12L).outcome());

        AutoToolSwapRoundResult freeze = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 2L, AutoToolSwapAction.FREEZE, 0, 9, ORIGINAL, CANDIDATE),
                fixture.inventory, 13L);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, freeze.outcome());
        Assert.assertEquals(3L, freeze.nextActionSequence());
        Assert.assertEquals(0, fixture.inventory.swapCount);
    }

    @Test
    public void swapAppliesOnceThenSyncsAndReplayDoesNotWriteAgain() {
        Fixture fixture = fixture();
        AutoToolSwapIntent swap = swapIntent(fixture, 1L);

        AutoToolSwapRoundResult applied = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                swap, fixture.inventory, 10L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, applied.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.SWAPPED, applied.roundState());
        Assert.assertEquals(1, fixture.inventory.swapCount);
        Assert.assertEquals(1, fixture.inventory.syncCount);
        Assert.assertTrue(fixture.service.snapshot(fixture.player).hasLedger());
        Assert.assertEquals(applied, fixture.service.handleIntent(fixture.player, fixture.endpoint,
                swap, fixture.inventory, 11L));
        Assert.assertEquals(1, fixture.inventory.swapCount);
        Assert.assertEquals(1, fixture.inventory.syncCount);
    }

    @Test
    public void everySwapSafetyGateAndPrewriteFailureProducesNoWrite() {
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.alive = false;
            }
        });
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.creative = true;
            }
        });
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.window0 = false;
            }
        });
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.cursorEmpty = false;
            }
        });
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.selectedSlot = 1;
            }
        });
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.slots[9] = AutoToolSwapStackState.empty();
            }
        });
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.slots[9] = stack("mod:drill", "candidate", 1);
            }
        });
        assertSwapRejected(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.failRead = true;
            }
        });

        Fixture fixture = fixture();
        AutoToolSwapIntent sameSlot = intent(fixture.roundId, 1L, AutoToolSwapAction.SWAP, 0, 0,
                ORIGINAL, ORIGINAL);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, sameSlot, fixture.inventory, 10L).outcome());
        Assert.assertEquals(0, fixture.inventory.swapCount);
    }

    @Test
    public void endpointProtocolRoundAndCandidateChangesRejectBeforeInventoryWrite() {
        Fixture fixture = fixture();
        AutoToolSwapIntent swap = swapIntent(fixture, 1L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                new Object(), swap, fixture.inventory, 1L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, new AutoToolSwapIntent(99, fixture.roundId, 1L, AutoToolSwapAction.SWAP,
                        0, 9, ORIGINAL.contentFingerprint(), CANDIDATE.contentFingerprint()),
                fixture.inventory, 2L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, intent(fixture.roundId + 1L, 1L, AutoToolSwapAction.SWAP, 0, 9,
                        ORIGINAL, CANDIDATE), fixture.inventory, 3L).outcome());
        Assert.assertEquals(1L, fixture.service.snapshot(fixture.player).nextActionSequence());
        Assert.assertEquals(0, fixture.inventory.readCount);

        fixture.inventory.slots[9] = stack("mod:drill", "changed", 39);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, swap, fixture.inventory, 4L).outcome());
        Assert.assertEquals(0, fixture.inventory.swapCount);
        Assert.assertEquals(0, fixture.inventory.syncCount);
    }

    @Test
    public void swapOrSyncExceptionsOrphanAndCachedReplayNeverWritesTwice() {
        Fixture swapFailure = fixture();
        swapFailure.inventory.failSwap = true;
        AutoToolSwapIntent first = swapIntent(swapFailure, 1L);
        AutoToolSwapRoundResult failedSwap = swapFailure.service.handleIntent(swapFailure.player,
                swapFailure.endpoint, first, swapFailure.inventory, 1L);
        Assert.assertEquals(AutoToolSwapResultCode.SYNC_FAILED, failedSwap.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED, failedSwap.roundState());
        Assert.assertEquals(1, swapFailure.inventory.swapCount);
        Assert.assertEquals(failedSwap, swapFailure.service.handleIntent(swapFailure.player,
                swapFailure.endpoint, first, swapFailure.inventory, 2L));
        Assert.assertEquals(1, swapFailure.inventory.swapCount);

        Fixture syncFailure = fixture();
        syncFailure.inventory.failSync = true;
        AutoToolSwapIntent second = swapIntent(syncFailure, 1L);
        AutoToolSwapRoundResult failedSync = syncFailure.service.handleIntent(syncFailure.player,
                syncFailure.endpoint, second, syncFailure.inventory, 3L);
        Assert.assertEquals(AutoToolSwapResultCode.SYNC_FAILED, failedSync.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED, failedSync.roundState());
        Assert.assertEquals(1, syncFailure.inventory.swapCount);
        Assert.assertEquals(1, syncFailure.inventory.syncCount);
        Assert.assertEquals(failedSync, syncFailure.service.handleIntent(syncFailure.player,
                syncFailure.endpoint, second, syncFailure.inventory, 4L));
        Assert.assertEquals(1, syncFailure.inventory.swapCount);
        Assert.assertEquals(1, syncFailure.inventory.syncCount);
    }

    @Test
    public void restoreUsesCurrentRealStacksAndAllowsDynamicOrEmptyActiveTool() {
        assertRestoreApplied(stack("mod:drill", "used", 20));
        assertRestoreApplied(AutoToolSwapStackState.empty());
    }

    @Test
    public void restoreRejectsOriginalAnchorRoleAndIntentRacesWithoutWriting() {
        Fixture originalChanged = swappedFixture();
        originalChanged.inventory.slots[9] = stack("mod:pickaxe", "changed", 89);
        assertRestoreRejected(originalChanged, currentRestoreIntent(originalChanged, 2L));

        Fixture roleChanged = swappedFixture();
        roleChanged.inventory.slots[0] = stack("mod:hammer", "used", 20);
        assertRestoreRejected(roleChanged, currentRestoreIntent(roleChanged, 2L));

        Fixture intentStale = swappedFixture();
        AutoToolSwapIntent stale = intent(intentStale.roundId, 2L, AutoToolSwapAction.RESTORE, 0, 9,
                CANDIDATE, ORIGINAL);
        intentStale.inventory.slots[0] = stack("mod:drill", "used", 20);
        assertRestoreRejected(intentStale, stale);
    }

    @Test
    public void freezeCloseAndKeyReleasePreserveLedgerUntilExplicitRestore() {
        Fixture fixture = swappedFixture();
        AutoToolSwapRoundResult frozen = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 2L, AutoToolSwapAction.FREEZE, 0, 9,
                        fixture.inventory.slots[0], fixture.inventory.slots[9]), fixture.inventory, 2L);
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, frozen.roundState());
        AutoToolSwapRoundResult blockedSwap = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 3L, AutoToolSwapAction.SWAP, 0, 9,
                        fixture.inventory.slots[0], fixture.inventory.slots[9]), fixture.inventory, 3L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, blockedSwap.outcome());

        long released = fixture.service.onKeyReleased(fixture.player, fixture.endpoint);
        Assert.assertEquals(fixture.roundId, released);
        Assert.assertEquals(AutoToolSwapRoundState.CLOSING, fixture.service.snapshot(fixture.player).roundState());
        Assert.assertTrue(fixture.service.snapshot(fixture.player).hasLedger());
        AutoToolSwapRoundResult close = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 4L, AutoToolSwapAction.CLOSE, 0, 9,
                        fixture.inventory.slots[0], fixture.inventory.slots[9]), fixture.inventory, 4L);
        Assert.assertEquals(AutoToolSwapResultCode.RESTORE_REQUIRED, close.outcome());
        Assert.assertEquals(1, fixture.inventory.swapCount);

        AutoToolSwapRoundResult restored = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                currentRestoreIntent(fixture, 5L), fixture.inventory, 5L);
        Assert.assertEquals(AutoToolSwapRoundState.CLOSING, restored.roundState());
        Assert.assertFalse(fixture.service.snapshot(fixture.player).hasLedger());
        AutoToolSwapRoundResult finished = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 6L, AutoToolSwapAction.CLOSE, 0, 9,
                        fixture.inventory.slots[0], fixture.inventory.slots[9]), fixture.inventory, 6L);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, finished.roundState());
    }

    @Test
    public void cleanupClearAndPhaseSequenceNeverTouchInventoryAndIsolateOldRounds() {
        Fixture first = fixture();
        Assert.assertEquals(1L, first.service.nextPhaseSequence(first.player, first.endpoint, first.roundId));
        Assert.assertEquals(0L, first.service.nextPhaseSequence(first.player, new Object(), first.roundId));
        Assert.assertEquals(0L, first.service.nextPhaseSequence(first.player, first.endpoint, first.roundId + 1L));
        first.service.cleanup(first.player);
        Assert.assertNull(first.service.snapshot(first.player));
        Assert.assertEquals(0, first.inventory.readCount);
        Assert.assertEquals(0, first.inventory.swapCount);

        first.service.beginRound(first.player, first.endpoint, 99L, 4L);
        long secondRound = first.service.activatePendingRound(first.player, first.endpoint, 5L).serverRoundId();
        Assert.assertEquals(0L, first.service.nextPhaseSequence(first.player, first.endpoint, first.roundId));
        Assert.assertEquals(1L, first.service.nextPhaseSequence(first.player, first.endpoint, secondRound));
        first.service.clearAll();
        Assert.assertNull(first.service.snapshot(first.player));
        Assert.assertEquals(0, first.inventory.syncCount);
    }

    private static void assertSwapRejected(InventoryMutation mutation) {
        Fixture fixture = fixture();
        mutation.apply(fixture.inventory);
        AutoToolSwapRoundResult result = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                swapIntent(fixture, 1L), fixture.inventory, 1L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, result.outcome());
        Assert.assertEquals(0, fixture.inventory.swapCount);
        Assert.assertEquals(0, fixture.inventory.syncCount);
    }

    private static void assertRestoreApplied(AutoToolSwapStackState activeTool) {
        Fixture fixture = swappedFixture();
        fixture.inventory.slots[0] = activeTool;
        AutoToolSwapRoundResult restored = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                currentRestoreIntent(fixture, 2L), fixture.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, restored.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, restored.roundState());
        Assert.assertEquals(2, fixture.inventory.swapCount);
        Assert.assertEquals(2, fixture.inventory.syncCount);
        Assert.assertFalse(fixture.service.snapshot(fixture.player).hasLedger());
        Assert.assertTrue(ORIGINAL.sameContent(fixture.inventory.slots[0]));
    }

    private static void assertRestoreRejected(Fixture fixture, AutoToolSwapIntent restore) {
        int swapsBefore = fixture.inventory.swapCount;
        int syncsBefore = fixture.inventory.syncCount;
        AutoToolSwapRoundResult result = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                restore, fixture.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, result.outcome());
        Assert.assertEquals(swapsBefore, fixture.inventory.swapCount);
        Assert.assertEquals(syncsBefore, fixture.inventory.syncCount);
        Assert.assertTrue(fixture.service.snapshot(fixture.player).hasLedger());
    }

    private static Fixture fixture() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService();
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 17L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        return new Fixture(service, player, endpoint, roundId, inventory());
    }

    private static Fixture swappedFixture() {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, swapIntent(fixture, 1L), fixture.inventory, 1L).outcome());
        return fixture;
    }

    private static FakeInventory inventory() {
        FakeInventory inventory = new FakeInventory();
        inventory.slots[0] = ORIGINAL;
        inventory.slots[9] = CANDIDATE;
        return inventory;
    }

    private static AutoToolSwapIntent swapIntent(Fixture fixture, long sequence) {
        return intent(fixture.roundId, sequence, AutoToolSwapAction.SWAP, 0, 9, ORIGINAL, CANDIDATE);
    }

    private static AutoToolSwapIntent currentRestoreIntent(Fixture fixture, long sequence) {
        return intent(fixture.roundId, sequence, AutoToolSwapAction.RESTORE, 0, 9,
                fixture.inventory.slots[0], fixture.inventory.slots[9]);
    }

    private static AutoToolSwapIntent intent(long roundId, long sequence, AutoToolSwapAction action,
            int anchorSlot, int candidateSlot, AutoToolSwapStackState anchor, AutoToolSwapStackState candidate) {
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, roundId, sequence, action,
                anchorSlot, candidateSlot, anchor.contentFingerprint(), candidate.contentFingerprint());
    }

    private static AutoToolSwapStackState stack(String role, String dynamic, int durability) {
        return AutoToolSwapStackState.occupied(role, AutoToolSwapContentFingerprint.fromContent(role, dynamic),
                durability);
    }

    private interface InventoryMutation {

        void apply(FakeInventory inventory);
    }

    private static final class Fixture {

        private final AutoToolSwapRoundService service;
        private final UUID player;
        private final Object endpoint;
        private final long roundId;
        private final FakeInventory inventory;

        private Fixture(AutoToolSwapRoundService service, UUID player, Object endpoint, long roundId,
                FakeInventory inventory) {
            this.service = service;
            this.player = player;
            this.endpoint = endpoint;
            this.roundId = roundId;
            this.inventory = inventory;
        }
    }

    private static final class FakeInventory implements AutoToolSwapInventoryPort {

        private final AutoToolSwapStackState[] slots = new AutoToolSwapStackState[36];
        private boolean alive = true;
        private boolean creative;
        private boolean window0 = true;
        private boolean cursorEmpty = true;
        private int selectedSlot;
        private boolean failRead;
        private boolean failSwap;
        private boolean failSync;
        private int readCount;
        private int swapCount;
        private int syncCount;

        @Override
        public boolean isPlayerAlive() {
            return alive;
        }

        @Override
        public boolean isCreativeMode() {
            return creative;
        }

        @Override
        public boolean hasPersonalInventoryWindow0() {
            return window0;
        }

        @Override
        public boolean isCursorEmpty() {
            return cursorEmpty;
        }

        @Override
        public int selectedHotbarSlot() {
            return selectedSlot;
        }

        @Override
        public AutoToolSwapStackState readInventorySlot(int inventorySlot) {
            readCount++;
            if (failRead) {
                throw new IllegalStateException("read failure");
            }
            AutoToolSwapStackState state = slots[inventorySlot];
            return state == null ? AutoToolSwapStackState.empty() : state;
        }

        @Override
        public void swapInventorySlotsAtomically(int anchorSlot, int candidateSlot) {
            swapCount++;
            if (failSwap) {
                throw new IllegalStateException("swap outcome unknown");
            }
            AutoToolSwapStackState value = slots[anchorSlot];
            slots[anchorSlot] = slots[candidateSlot];
            slots[candidateSlot] = value;
        }

        @Override
        public void syncInventoryDifference() {
            syncCount++;
            if (failSync) {
                throw new IllegalStateException("sync failure");
            }
        }
    }
}
