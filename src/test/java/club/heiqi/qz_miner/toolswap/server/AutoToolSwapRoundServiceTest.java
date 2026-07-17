package club.heiqi.qz_miner.toolswap.server;

import java.util.ArrayList;
import java.util.List;
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

/** 服务端工具换位 round 的纯 JVM 事务合同。 */
public class AutoToolSwapRoundServiceTest {

    private static final AutoToolSwapStackState ORIGINAL = stack("mod:pickaxe", "original", 90);
    private static final AutoToolSwapStackState CANDIDATE = stack("mod:drill", "candidate", 40);

    @Test
    public void pendingActivationIsEndpointBoundIdempotentAndRoundIdsDoNotReuse() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
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
        Assert.assertEquals(0L, service.currentRoundId(player));
        Assert.assertEquals(0L, service.currentRoundId(player, endpoint));
        service.beginRound(player, endpoint, 22L, 6L);
        AutoToolSwapRoundResult second = service.activatePendingRound(player, endpoint, 7L);
        Assert.assertEquals(2L, second.serverRoundId());
    }

    @Test
    public void publicServicesShareProcessRoundIdsAcrossInstanceRecreation() {
        AutoToolSwapRoundService firstService = new AutoToolSwapRoundService();
        long firstRoundId = activateRound(firstService);
        AutoToolSwapRoundService secondService = new AutoToolSwapRoundService();
        long secondRoundId = activateRound(secondService);
        firstService = null;
        secondService = null;
        long recreatedRoundId = activateRound(new AutoToolSwapRoundService());

        Assert.assertTrue(firstRoundId > 0L);
        Assert.assertTrue(secondRoundId > 0L);
        Assert.assertTrue(recreatedRoundId > 0L);
        Assert.assertNotEquals(firstRoundId, secondRoundId);
        Assert.assertNotEquals(firstRoundId, recreatedRoundId);
        Assert.assertNotEquals(secondRoundId, recreatedRoundId);
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
    public void serverSwapUsesTheSharedTwoPointDurabilityReserve() {
        assertSwapDurabilityOutcome(0, AutoToolSwapResultCode.REJECTED);
        assertSwapDurabilityOutcome(1, AutoToolSwapResultCode.REJECTED);
        assertSwapDurabilityOutcome(2, AutoToolSwapResultCode.APPLIED);
        assertSwapDurabilityOutcome(Integer.MAX_VALUE, AutoToolSwapResultCode.APPLIED);
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
    public void swapAndRestoreWriteFailuresOrphanAndCachedReplayNeverWritesTwice() {
        assertWriteFailureOrphans(AutoToolSwapAction.SWAP, FailureMode.RUNTIME, false);
        assertWriteFailureOrphans(AutoToolSwapAction.SWAP, FailureMode.LINKAGE, false);
        assertWriteFailureOrphans(AutoToolSwapAction.SWAP, FailureMode.RUNTIME, true);
        assertWriteFailureOrphans(AutoToolSwapAction.SWAP, FailureMode.LINKAGE, true);
        assertWriteFailureOrphans(AutoToolSwapAction.RESTORE, FailureMode.RUNTIME, false);
        assertWriteFailureOrphans(AutoToolSwapAction.RESTORE, FailureMode.LINKAGE, false);
        assertWriteFailureOrphans(AutoToolSwapAction.RESTORE, FailureMode.RUNTIME, true);
        assertWriteFailureOrphans(AutoToolSwapAction.RESTORE, FailureMode.LINKAGE, true);
    }

    @Test
    public void restoreUsesCurrentRealStacksAllowsDynamicOrEmptyActiveToolAndPreservesManualSelection() {
        assertRestoreApplied(stack("mod:drill", "used", 20));
        assertRestoreApplied(AutoToolSwapStackState.empty());

        Fixture manuallySelected = swappedFixture();
        manuallySelected.inventory.selectedSlot = 1;
        AutoToolSwapRoundResult restored = manuallySelected.service.handleIntent(manuallySelected.player,
                manuallySelected.endpoint, currentRestoreIntent(manuallySelected, 2L), manuallySelected.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, restored.outcome());
        Assert.assertEquals(1, manuallySelected.inventory.selectedSlot);
    }

    @Test
    public void restoreKeepsSafetyContextGatesWithoutWriting() {
        assertRestoreRejectedByContext(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.alive = false;
            }
        });
        assertRestoreRejectedByContext(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.creative = true;
            }
        });
        assertRestoreRejectedByContext(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.window0 = false;
            }
        });
        assertRestoreRejectedByContext(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.cursorEmpty = false;
            }
        });
        assertRestoreRejectedByContext(new InventoryMutation() {
            @Override
            public void apply(FakeInventory inventory) {
                inventory.failRead = true;
            }
        });
    }

    @Test
    public void restoreAllowsOriginalAnchorDynamicsButRejectsRoleAndIntentRacesWithoutWriting() {
        Fixture originalChanged = swappedFixture();
        originalChanged.inventory.slots[9] = stack("mod:pickaxe", "changed", 89);
        AutoToolSwapRoundResult changed = originalChanged.service.handleIntent(originalChanged.player,
                originalChanged.endpoint, currentRestoreIntent(originalChanged, 2L), originalChanged.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, changed.outcome());
        Assert.assertTrue(originalChanged.inventory.slots[0].sameRole(ORIGINAL));

        Fixture roleChanged = swappedFixture();
        roleChanged.inventory.slots[0] = stack("mod:hammer", "used", 20);
        assertRestoreRejected(roleChanged, currentRestoreIntent(roleChanged, 2L));

        Fixture subtypeChanged = swappedFixture();
        subtypeChanged.inventory.slots[9] = stack("mod:pickaxe@1", "changed", 89);
        assertRestoreRejected(subtypeChanged, currentRestoreIntent(subtypeChanged, 2L));

        Fixture intentStale = swappedFixture();
        AutoToolSwapIntent stale = intent(intentStale.roundId, 2L, AutoToolSwapAction.RESTORE, 0, 9,
                CANDIDATE, ORIGINAL);
        intentStale.inventory.slots[0] = stack("mod:drill", "used", 20);
        assertRestoreRejected(intentStale, stale);
    }

    @Test
    public void emptyOriginalAnchorRestoresByExchangingAnyCurrentCandidateOccupant() {
        Fixture emptyAnchor = fixture();
        emptyAnchor.inventory.slots[0] = AutoToolSwapStackState.empty();
        AutoToolSwapIntent swap = intent(emptyAnchor.roundId, 1L, AutoToolSwapAction.SWAP, 0, 9,
                AutoToolSwapStackState.empty(), CANDIDATE);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, emptyAnchor.service.handleIntent(emptyAnchor.player,
                emptyAnchor.endpoint, swap, emptyAnchor.inventory, 1L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, emptyAnchor.service.handleIntent(emptyAnchor.player,
                emptyAnchor.endpoint, currentRestoreIntent(emptyAnchor, 2L), emptyAnchor.inventory, 2L).outcome());

        Fixture occupiedEmptyLease = fixture();
        AutoToolSwapStackState originalEmpty = AutoToolSwapStackState.empty();
        occupiedEmptyLease.inventory.slots[0] = originalEmpty;
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, occupiedEmptyLease.service.handleIntent(
                occupiedEmptyLease.player, occupiedEmptyLease.endpoint,
                intent(occupiedEmptyLease.roundId, 1L, AutoToolSwapAction.SWAP, 0, 9,
                        originalEmpty, CANDIDATE), occupiedEmptyLease.inventory, 1L).outcome());
        AutoToolSwapStackState borrowedTool = occupiedEmptyLease.inventory.slots[0];
        AutoToolSwapStackState occupyingDrop = stack("mod:foreign", "occupied", 10);
        occupiedEmptyLease.inventory.slots[9] = occupyingDrop;

        AutoToolSwapRoundResult restored = occupiedEmptyLease.service.handleIntent(occupiedEmptyLease.player,
                occupiedEmptyLease.endpoint, currentRestoreIntent(occupiedEmptyLease, 2L),
                occupiedEmptyLease.inventory, 2L);

        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, restored.outcome());
        Assert.assertSame("当前占位物必须原样交换到主手", occupyingDrop, occupiedEmptyLease.inventory.slots[0]);
        Assert.assertSame("借用工具必须原样回到候选槽", borrowedTool, occupiedEmptyLease.inventory.slots[9]);
        Assert.assertEquals(2, occupiedEmptyLease.inventory.swapCount);
        Assert.assertEquals(2, occupiedEmptyLease.inventory.syncCount);
        Assert.assertFalse(occupiedEmptyLease.service.snapshot(occupiedEmptyLease.player).hasLedger());
    }

    @Test
    public void abandonUsesCanonicalControlClearsLedgerWithoutInventoryAndReplaysIdempotently() {
        Fixture fixture = swappedFixture();
        fixture.inventory.readCount = 0;
        fixture.inventory.swapCount = 0;
        fixture.inventory.syncCount = 0;
        AutoToolSwapIntent abandon = abandonIntent(fixture, 2L);

        AutoToolSwapRoundResult finished = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                abandon, fixture.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, finished.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, finished.roundState());
        Assert.assertFalse(fixture.service.snapshot(fixture.player).hasLedger());
        assertNoInventoryAccess(fixture.inventory);
        Assert.assertEquals(finished, fixture.service.handleIntent(fixture.player, fixture.endpoint,
                abandon, fixture.inventory, 3L));
        assertNoInventoryAccess(fixture.inventory);

        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED,
                fixture.service.beginRound(fixture.player, fixture.endpoint, 99L, 4L).outcome());
        long nextRound = fixture.service.activatePendingRound(fixture.player, fixture.endpoint, 5L).serverRoundId();
        Assert.assertTrue(nextRound > fixture.roundId);
    }

    @Test
    public void abandonMismatchesAndNoncanonicalControlsCannotClearCurrentLedger() {
        Fixture fixture = swappedFixture();
        fixture.inventory.readCount = 0;
        fixture.inventory.swapCount = 0;
        fixture.inventory.syncCount = 0;

        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                new Object(), abandonIntent(fixture, 2L), fixture.inventory, 2L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                        fixture.roundId + 1L, 2L, AutoToolSwapAction.ABANDON, 0, 9,
                        AutoToolSwapContentFingerprint.canonicalEmpty(),
                        AutoToolSwapContentFingerprint.canonicalEmpty()), fixture.inventory, 2L).outcome());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, abandonIntent(fixture, 3L), fixture.inventory, 2L).outcome());
        Assert.assertTrue(fixture.service.snapshot(fixture.player).hasLedger());
        assertNoInventoryAccess(fixture.inventory);

        Fixture wrongSlots = swappedFixture();
        wrongSlots.inventory.readCount = 0;
        wrongSlots.inventory.swapCount = 0;
        wrongSlots.inventory.syncCount = 0;
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent wrongSlotIntent = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                wrongSlots.roundId, 2L, AutoToolSwapAction.ABANDON, 0, 8, empty, empty);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, wrongSlots.service.handleIntent(wrongSlots.player,
                wrongSlots.endpoint, wrongSlotIntent, wrongSlots.inventory, 2L).outcome());
        Assert.assertTrue(wrongSlots.service.snapshot(wrongSlots.player).hasLedger());
        assertNoInventoryAccess(wrongSlots.inventory);

        AutoToolSwapIntent noncanonical = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, 2L, AutoToolSwapAction.ABANDON, 0, 9,
                ORIGINAL.contentFingerprint(), AutoToolSwapContentFingerprint.canonicalEmpty());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, noncanonical, fixture.inventory, 3L).outcome());
        Assert.assertTrue(fixture.service.snapshot(fixture.player).hasLedger());
        assertNoInventoryAccess(fixture.inventory);

        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, abandonIntent(fixture, 3L), fixture.inventory, 4L).roundState());
        assertNoInventoryAccess(fixture.inventory);
    }

    @Test
    public void abandonIsAcceptedFromFrozenAndClosingLedgerStates() {
        Fixture frozen = swappedFixture();
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, frozen.service.handleIntent(frozen.player,
                frozen.endpoint, intent(frozen.roundId, 2L, AutoToolSwapAction.FREEZE, 0, 9,
                        frozen.inventory.slots[0], frozen.inventory.slots[9]), frozen.inventory, 2L).roundState());
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, frozen.service.handleIntent(frozen.player,
                frozen.endpoint, abandonIntent(frozen, 3L), frozen.inventory, 3L).roundState());

        Fixture closing = swappedFixture();
        closing.service.onKeyReleased(closing.player, closing.endpoint);
        Assert.assertEquals(AutoToolSwapRoundState.FINISHED, closing.service.handleIntent(closing.player,
                closing.endpoint, abandonIntent(closing, 2L), closing.inventory, 2L).roundState());
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

    @Test
    public void observedChainPhasesFreezeSwapCloseRoundAndKeepClientFreezeIdempotent() {
        Fixture fixture = fixture();
        Assert.assertEquals(1L, fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId,
                false, false));
        Assert.assertEquals(AutoToolSwapRoundState.OPEN, fixture.service.snapshot(fixture.player).roundState());

        Assert.assertEquals(2L, fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId,
                true, false));
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, fixture.service.snapshot(fixture.player).roundState());
        Fixture running = fixture();
        Assert.assertEquals(1L, running.service.observeChainPhase(running.player, running.endpoint, running.roundId,
                true, false));
        Assert.assertEquals(AutoToolSwapRoundState.FROZEN, running.service.snapshot(running.player).roundState());
        AutoToolSwapRoundResult freeze = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 1L, AutoToolSwapAction.FREEZE, 0, 9, ORIGINAL, CANDIDATE),
                fixture.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, freeze.outcome());
        Assert.assertEquals(2L, fixture.service.snapshot(fixture.player).phaseSequence());

        Assert.assertEquals(3L, fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId,
                false, true));
        Assert.assertEquals(AutoToolSwapRoundState.CLOSING, fixture.service.snapshot(fixture.player).roundState());
        Assert.assertFalse(fixture.service.snapshot(fixture.player).keyDown());
        Assert.assertEquals(4L, fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId,
                false, true));
        Assert.assertEquals(AutoToolSwapRoundState.CLOSING, fixture.service.snapshot(fixture.player).roundState());
    }

    @Test
    public void observedChainPhaseRejectsMismatchesAndOrphansOnPhaseSequenceOverflow() {
        Fixture fixture = fixture();
        Assert.assertEquals(0L, fixture.service.observeChainPhase(fixture.player, new Object(), fixture.roundId,
                true, false));
        Assert.assertEquals(0L, fixture.service.observeChainPhase(fixture.player, fixture.endpoint,
                fixture.roundId + 1L, true, false));

        AutoToolSwapRoundService overflow = new AutoToolSwapRoundService(0L,
                AutoToolSwapProtocol.FIRST_ACTION_SEQUENCE, Long.MAX_VALUE);
        overflow.beginRound(fixture.player, fixture.endpoint, 99L, 0L);
        long overflowRoundId = overflow.activatePendingRound(fixture.player, fixture.endpoint, 1L).serverRoundId();
        Assert.assertEquals(0L, overflow.observeChainPhase(fixture.player, fixture.endpoint, overflowRoundId,
                true, false));
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED, overflow.snapshot(fixture.player).roundState());
        Assert.assertEquals(0L, overflow.currentRoundId(fixture.player, fixture.endpoint));
    }

    @Test
    public void actionDiagnosticIsOncePerActionBoundedAndDoesNotExposeFullNbt() {
        final List<String> logs = new ArrayList<String>();
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L,
                new AutoToolSwapRoundService.DiagnosticSink() {
                    @Override
                    public void log(String message) {
                        logs.add(message);
                    }
                });
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        FakeInventory inventory = inventory();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();

        AutoToolSwapRoundResult result = service.handleIntent(player, endpoint,
                intent(roundId, 1L, AutoToolSwapAction.SWAP, 0, 9, ORIGINAL, CANDIDATE), inventory, 2L);

        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, result.outcome());
        Assert.assertEquals("单个动作只能输出一条前后快照", 1, logs.size());
        String log = logs.get(0);
        Assert.assertTrue(log.contains("[AutoToolSwapDiag]"));
        Assert.assertTrue(log.contains("round=" + roundId));
        Assert.assertTrue(log.contains("actionSeq=1"));
        Assert.assertTrue(log.contains("reason=none"));
        Assert.assertTrue(log.contains("contentHash=short"));
        Assert.assertFalse(log.contains("secret-nbt"));
    }

    @Test
    public void failingDiagnosticSinkCannotChangeSwapOutcome() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L,
                new AutoToolSwapRoundService.DiagnosticSink() {
                    @Override
                    public void log(String message) {
                        throw new IllegalStateException("diagnostic failure");
                    }
                });
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        FakeInventory inventory = inventory();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();

        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, service.handleIntent(player, endpoint,
                intent(roundId, 1L, AutoToolSwapAction.SWAP, 0, 9, ORIGINAL, CANDIDATE), inventory, 2L).outcome());
        Assert.assertEquals(1, inventory.swapCount);
    }

    @Test
    public void takeoverWithoutLedgerSwapsOnceAndDeclineNeverReadsInventory() {
        Fixture fixture = fixture();
        fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId, true, false);
        AutoToolSwapStackState low = stack("mod:pickaxe", "low", 1);
        AutoToolSwapStackState next = stack("mod:drill2", "fresh", 80);
        fixture.inventory.slots[0] = low;
        fixture.inventory.slots[7] = next;
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 0, 0, fixture.inventory.slots[0], 10L, 18L);
        Assert.assertNotNull(request);

        AutoToolSwapRoundResult applied = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 1L, AutoToolSwapAction.TAKEOVER, 0, 7,
                        fixture.inventory.slots[0], fixture.inventory.slots[7]), fixture.inventory, 11L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, applied.outcome());
        Assert.assertSame("候选引用进入主手", next, fixture.inventory.slots[0]);
        Assert.assertSame("低耐久主手进入候选槽", low, fixture.inventory.slots[7]);
        Assert.assertEquals(1, fixture.inventory.swapCount);
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.APPLIED,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 12L));

        Fixture declined = fixture();
        declined.service.observeChainPhase(declined.player, declined.endpoint, declined.roundId, true, false);
        declined.inventory.slots[0] = stack("mod:pickaxe", "low", 1);
        AutoToolSwapTakeoverRequest declineRequest = declined.service.prepareTakeover(declined.player,
                declined.endpoint, declined.roundId, 2, 1, 64, 2, 1, 0, 0,
                declined.inventory.slots[0], 20L, 28L);
        declined.inventory.readCount = 0;
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent decline = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                declined.roundId, declineRequest.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER,
                0, 0, empty, empty);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, declined.service.handleIntent(declined.player,
                declined.endpoint, decline, declined.inventory, 21L).outcome());
        Assert.assertEquals(0, declined.inventory.readCount);
        Assert.assertEquals(0, declined.inventory.swapCount);
        Assert.assertEquals(0, declined.inventory.syncCount);
    }

    @Test
    public void staleClientAnchorEchoDoesNotRejectExactServerAnchorAndCandidate() {
        Fixture fixture = takeoverFixture();
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 24902, 0, fixture.inventory.slots[0], 10L, 18L);
        AutoToolSwapStackState staleClientEcho = stack("mod:pickaxe", "client-old-durability", 2);
        AutoToolSwapIntent intent = intent(fixture.roundId, request.actionSequence(),
                AutoToolSwapAction.TAKEOVER, 0, 7, staleClientEcho, fixture.inventory.slots[7]);

        AutoToolSwapRoundResult applied = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent, fixture.inventory, 11L);

        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, applied.outcome());
        Assert.assertEquals(1, fixture.inventory.swapCount);
        Assert.assertEquals(1, fixture.inventory.syncCount);
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.APPLIED,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 12L));
    }

    @Test
    public void takeoverDiagnosticsGiveOneBoundedReasonForEverySettlementGate() {
        assertTakeoverReason(TakeoverReasonCase.INVENTORY_CONTEXT, "inventory-context",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.SELECTED_SLOT, "selected-slot",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.PENDING_ANCHOR_CHANGED, "pending-anchor-changed",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.CANDIDATE_FINGERPRINT, "candidate-fingerprint",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.CANDIDATE_LOW_RESERVE, "candidate-low-reserve",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.LEDGER_OLD_ROLE, "ledger-old-role",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.LEDGER_ACTIVE_ROLE, "ledger-active-role",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.SLOT_CONFLICT, "slot-conflict",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.INVENTORY_READ_FAILED, "inventory-read-failed",
                AutoToolSwapResultCode.REJECTED);
        assertTakeoverReason(TakeoverReasonCase.APPLIED, "applied", AutoToolSwapResultCode.APPLIED);
        assertTakeoverReason(TakeoverReasonCase.SYNC_FAILED, "sync-failed",
                AutoToolSwapResultCode.SYNC_FAILED);
    }

    @Test
    public void takeoverWithLedgerUsesOneThreeSlotRotationAndFinalRestoreIsReversible() {
        Fixture fixture = swappedFixture();
        fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId, true, false);
        AutoToolSwapStackState lowActive = stack("mod:drill", "used-low", 1);
        AutoToolSwapStackState next = stack("mod:hammer", "fresh", 70);
        fixture.inventory.slots[0] = lowActive;
        fixture.inventory.slots[7] = next;
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 5, 4, 70, 6, 2, 0, 0, lowActive, 30L, 38L);

        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, intent(fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER,
                        0, 7, lowActive, next), fixture.inventory, 31L).outcome());
        Assert.assertEquals(1, fixture.inventory.rotateCount);
        Assert.assertSame(next, fixture.inventory.slots[0]);
        Assert.assertSame(lowActive, fixture.inventory.slots[9]);
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[7]);
        Assert.assertEquals(7, fixture.service.snapshot(fixture.player).ledgerCandidateSlot());

        fixture.service.consumeTakeoverGate(fixture.player, fixture.endpoint, request);
        AutoToolSwapStackState lowSecond = stack("mod:hammer", "used-low", 1);
        AutoToolSwapStackState third = stack("mod:excavator", "fresh", 90);
        fixture.inventory.slots[0] = lowSecond;
        fixture.inventory.slots[8] = third;
        AutoToolSwapTakeoverRequest secondRequest = fixture.service.prepareTakeover(fixture.player,
                fixture.endpoint, fixture.roundId, 5, 5, 70, 6, 2, 0, 0,
                lowSecond, 32L, 39L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, intent(fixture.roundId, secondRequest.actionSequence(),
                        AutoToolSwapAction.TAKEOVER, 0, 8, lowSecond, third), fixture.inventory, 33L).outcome());
        Assert.assertEquals(2, fixture.inventory.rotateCount);
        Assert.assertSame(third, fixture.inventory.slots[0]);
        Assert.assertSame(lowSecond, fixture.inventory.slots[7]);
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[8]);
        fixture.service.consumeTakeoverGate(fixture.player, fixture.endpoint, secondRequest);

        AutoToolSwapRoundResult restored = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                currentRestoreIntentForSlots(fixture, 4L, 0, 8), fixture.inventory, 34L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, restored.outcome());
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[0]);
        Assert.assertSame(third, fixture.inventory.slots[8]);
        Assert.assertSame(lowSecond, fixture.inventory.slots[7]);
        Assert.assertSame("已耗损旧工具不被回滚", lowActive, fixture.inventory.slots[9]);
    }

    @Test
    public void staleLowDurabilityAndDuplicateSlotTakeoversAreZeroWriteAndStopGate() {
        Fixture fixture = swappedFixture();
        fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId, true, false);
        AutoToolSwapStackState low = stack("mod:drill", "low", 1);
        fixture.inventory.slots[0] = low;
        fixture.inventory.slots[7] = stack("mod:hammer", "fresh", 1);
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 3, 1, 64, 1, 1, 0, 0, low, 1L, 8L);
        AutoToolSwapRoundResult rejected = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                        low, fixture.inventory.slots[7]), fixture.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, rejected.outcome());
        Assert.assertEquals(0, fixture.inventory.rotateCount);
        Assert.assertEquals(1, fixture.inventory.swapCount);
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.STOP,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 3L));
    }

    @Test
    public void exactStopDoesNotAffectMismatchedEndpointOrGateAndRejectsLateIntent() {
        Fixture fixture = takeoverFixture();
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 0, 0, fixture.inventory.slots[0], 10L, 18L);
        AutoToolSwapTakeoverRequest otherGate = new AutoToolSwapTakeoverRequest(
                AutoToolSwapProtocol.PROTOCOL_VERSION, fixture.roundId, request.actionSequence(),
                5, 1, 64, 2, 1, 0, 10L, 18L);

        Assert.assertFalse(fixture.service.stopTakeoverGate(fixture.player, new Object(), request));
        Assert.assertFalse(fixture.service.stopTakeoverGate(fixture.player, fixture.endpoint, otherGate));
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.WAITING,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 11L));
        Assert.assertTrue(fixture.service.stopTakeoverGate(fixture.player, fixture.endpoint, request));
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.STOP,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 11L));

        resetInventoryCounters(fixture.inventory);
        AutoToolSwapRoundResult rejected = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                takeoverIntent(fixture, request), fixture.inventory, 11L);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, rejected.outcome());
        assertZeroTakeoverInventoryAccess(fixture.inventory);
    }

    @Test
    public void takeoverDeadlineMinusOneAppliesAndExactReplayRemainsIdempotent() {
        Fixture fixture = takeoverFixture();
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 0, 0, fixture.inventory.slots[0], 10L, 18L);
        AutoToolSwapIntent intent = takeoverIntent(fixture, request);

        AutoToolSwapRoundResult applied = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent, fixture.inventory, 17L);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, applied.outcome());
        Assert.assertEquals(1, fixture.inventory.swapCount);
        Assert.assertEquals(1, fixture.inventory.syncCount);
        int diagnostics = fixture.inventory.diagnosticCount;
        int reads = fixture.inventory.readCount;

        Assert.assertEquals(applied, fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent, fixture.inventory, 18L));
        Assert.assertEquals("按时结算的完全重复包在 deadline 后仍只返回缓存结果",
                diagnostics, fixture.inventory.diagnosticCount);
        Assert.assertEquals(reads, fixture.inventory.readCount);
        Assert.assertEquals(1, fixture.inventory.swapCount);
        Assert.assertEquals(1, fixture.inventory.syncCount);
    }

    @Test
    public void declineDeadlineMinusOneIsAcceptedWithoutInventoryAccess() {
        Fixture fixture = takeoverFixture();
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 0, 0, fixture.inventory.slots[0], 10L, 18L);
        resetInventoryCounters(fixture.inventory);

        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, declineIntent(fixture, request), fixture.inventory, 17L).outcome());
        assertZeroTakeoverInventoryAccess(fixture.inventory);
    }

    @Test
    public void deadlineAndLaterTakeoverOrDeclineRejectBeforeEveryInventoryAccess() {
        assertLateTakeoverActionRejected(AutoToolSwapAction.TAKEOVER, 18L);
        assertLateTakeoverActionRejected(AutoToolSwapAction.TAKEOVER, 19L);
        assertLateTakeoverActionRejected(AutoToolSwapAction.DECLINE_TAKEOVER, 18L);
        assertLateTakeoverActionRejected(AutoToolSwapAction.DECLINE_TAKEOVER, 19L);
    }

    @Test
    public void mismatchedSequenceAtDeadlineStillStopsPendingWithoutInventoryAccess() {
        Fixture fixture = takeoverFixture();
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 0, 0, fixture.inventory.slots[0], 10L, 18L);
        AutoToolSwapIntent wrongSequence = intent(fixture.roundId, request.actionSequence() + 1L,
                AutoToolSwapAction.TAKEOVER, 0, 7, fixture.inventory.slots[0], fixture.inventory.slots[7]);
        resetInventoryCounters(fixture.inventory);

        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, wrongSequence, fixture.inventory, 18L).outcome());
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.STOP,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 18L));
        Assert.assertEquals(request.actionSequence(),
                fixture.service.snapshot(fixture.player).nextActionSequence());
        assertZeroTakeoverInventoryAccess(fixture.inventory);
    }

    private static void assertLateTakeoverActionRejected(AutoToolSwapAction action, long serverTick) {
        Fixture fixture = takeoverFixture();
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 0, 0, fixture.inventory.slots[0], 10L, 18L);
        AutoToolSwapIntent intent = action == AutoToolSwapAction.TAKEOVER
                ? takeoverIntent(fixture, request) : declineIntent(fixture, request);
        resetInventoryCounters(fixture.inventory);

        AutoToolSwapRoundResult rejected = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent, fixture.inventory, serverTick);
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED, rejected.outcome());
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.STOP,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, serverTick));
        assertZeroTakeoverInventoryAccess(fixture.inventory);
        long nextSequence = rejected.nextActionSequence();

        Assert.assertEquals("重复迟到 intent 必须返回同一缓存结果", rejected,
                fixture.service.handleIntent(fixture.player, fixture.endpoint, intent,
                        fixture.inventory, serverTick + 1L));
        Assert.assertEquals(nextSequence, fixture.service.snapshot(fixture.player).nextActionSequence());
        assertZeroTakeoverInventoryAccess(fixture.inventory);
    }

    private static void assertTakeoverReason(TakeoverReasonCase reasonCase, String expectedReason,
            AutoToolSwapResultCode expectedResult) {
        final List<String> logs = new ArrayList<String>();
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L,
                new AutoToolSwapRoundService.DiagnosticSink() {
                    @Override
                    public void log(String message) {
                        logs.add(message);
                    }
                });
        Fixture fixture = fixture(service);
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, swapIntent(fixture, 1L), fixture.inventory, 1L).outcome());
        fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId, true, false);
        fixture.inventory.slots[0] = stack("mod:drill", "active-low", 1);
        fixture.inventory.slots[7] = stack("mod:hammer", "fresh", 80);
        if (reasonCase == TakeoverReasonCase.CANDIDATE_LOW_RESERVE) {
            fixture.inventory.slots[7] = stack("mod:hammer", "low", 1);
        } else if (reasonCase == TakeoverReasonCase.LEDGER_ACTIVE_ROLE) {
            fixture.inventory.slots[0] = stack("mod:wrong-active", "low", 1);
        }
        AutoToolSwapTakeoverRequest request = fixture.service.prepareTakeover(fixture.player, fixture.endpoint,
                fixture.roundId, 4, 1, 64, 2, 1, 24902, 0, fixture.inventory.slots[0], 10L, 18L);
        AutoToolSwapIntent intent = intent(fixture.roundId, request.actionSequence(),
                AutoToolSwapAction.TAKEOVER, 0, 7, fixture.inventory.slots[0], fixture.inventory.slots[7]);
        if (reasonCase == TakeoverReasonCase.INVENTORY_CONTEXT) {
            fixture.inventory.alive = false;
        } else if (reasonCase == TakeoverReasonCase.SELECTED_SLOT) {
            fixture.inventory.selectedSlot = 1;
        } else if (reasonCase == TakeoverReasonCase.PENDING_ANCHOR_CHANGED) {
            fixture.inventory.slots[0] = stack("mod:drill", "active-changed", 1);
        } else if (reasonCase == TakeoverReasonCase.CANDIDATE_FINGERPRINT) {
            fixture.inventory.slots[7] = stack("mod:hammer", "candidate-changed", 79);
        } else if (reasonCase == TakeoverReasonCase.LEDGER_OLD_ROLE) {
            fixture.inventory.slots[9] = stack("mod:wrong-old", "changed", 90);
        } else if (reasonCase == TakeoverReasonCase.SLOT_CONFLICT) {
            intent = intent(fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER,
                    0, 9, fixture.inventory.slots[0], fixture.inventory.slots[9]);
        } else if (reasonCase == TakeoverReasonCase.INVENTORY_READ_FAILED) {
            fixture.inventory.failRead = true;
        } else if (reasonCase == TakeoverReasonCase.SYNC_FAILED) {
            fixture.inventory.syncFailure = FailureMode.RUNTIME;
        }
        logs.clear();
        resetInventoryCounters(fixture.inventory);

        AutoToolSwapRoundResult result = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent, fixture.inventory, 11L);

        Assert.assertEquals(expectedResult, result.outcome());
        Assert.assertEquals("每个 TAKEOVER 结算只能输出一条 action", 1, logs.size());
        Assert.assertTrue(logs.get(0).contains("action=TAKEOVER"));
        Assert.assertTrue(logs.get(0).contains("reason=" + expectedReason));
        Assert.assertFalse(logs.get(0).contains("secret-nbt"));
        if (expectedResult == AutoToolSwapResultCode.REJECTED) {
            Assert.assertEquals(0, fixture.inventory.swapCount);
            Assert.assertEquals(0, fixture.inventory.rotateCount);
            Assert.assertEquals(0, fixture.inventory.syncCount);
        } else {
            Assert.assertEquals(1, fixture.inventory.rotateCount);
            Assert.assertEquals(1, fixture.inventory.syncCount);
        }
    }

    private static Fixture takeoverFixture() {
        Fixture fixture = fixture();
        fixture.service.observeChainPhase(fixture.player, fixture.endpoint, fixture.roundId, true, false);
        fixture.inventory.slots[0] = stack("mod:pickaxe", "low", 1);
        fixture.inventory.slots[7] = stack("mod:drill2", "fresh", 80);
        return fixture;
    }

    private static AutoToolSwapIntent takeoverIntent(Fixture fixture, AutoToolSwapTakeoverRequest request) {
        return intent(fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                fixture.inventory.slots[0], fixture.inventory.slots[7]);
    }

    private static AutoToolSwapIntent declineIntent(Fixture fixture, AutoToolSwapTakeoverRequest request) {
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, fixture.roundId,
                request.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER, 0, 0, empty, empty);
    }

    private static void resetInventoryCounters(FakeInventory inventory) {
        inventory.contextReadCount = 0;
        inventory.readCount = 0;
        inventory.swapCount = 0;
        inventory.rotateCount = 0;
        inventory.syncCount = 0;
        inventory.diagnosticCount = 0;
    }

    private static void assertZeroTakeoverInventoryAccess(FakeInventory inventory) {
        Assert.assertEquals("不得读取库存上下文", 0, inventory.contextReadCount);
        Assert.assertEquals("不得读取槽位", 0, inventory.readCount);
        Assert.assertEquals("不得执行双槽写入", 0, inventory.swapCount);
        Assert.assertEquals("不得执行三槽写入", 0, inventory.rotateCount);
        Assert.assertEquals("不得同步库存", 0, inventory.syncCount);
        Assert.assertEquals("不得捕获库存诊断", 0, inventory.diagnosticCount);
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

    private static void assertSwapDurabilityOutcome(int remainingDurability,
            AutoToolSwapResultCode expectedOutcome) {
        Fixture fixture = fixture();
        fixture.inventory.slots[9] = stack("mod:drill", "durability-" + remainingDurability,
                remainingDurability);
        AutoToolSwapRoundResult result = fixture.service.handleIntent(fixture.player, fixture.endpoint,
                intent(fixture.roundId, 1L, AutoToolSwapAction.SWAP, 0, 9,
                        fixture.inventory.slots[0], fixture.inventory.slots[9]), fixture.inventory, 1L);
        Assert.assertEquals(expectedOutcome, result.outcome());
        Assert.assertEquals(expectedOutcome == AutoToolSwapResultCode.APPLIED ? 1 : 0,
                fixture.inventory.swapCount);
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

    private static void assertRestoreRejectedByContext(InventoryMutation mutation) {
        Fixture fixture = swappedFixture();
        mutation.apply(fixture.inventory);
        assertRestoreRejected(fixture, currentRestoreIntent(fixture, 2L));
    }

    private static void assertWriteFailureOrphans(AutoToolSwapAction action, FailureMode failureMode,
            boolean failSync) {
        Fixture fixture = action == AutoToolSwapAction.SWAP ? fixture() : swappedFixture();
        AutoToolSwapIntent intent = action == AutoToolSwapAction.SWAP ? swapIntent(fixture, 1L)
                : currentRestoreIntent(fixture, 2L);
        int swapsBefore = fixture.inventory.swapCount;
        int syncsBefore = fixture.inventory.syncCount;
        if (failSync) {
            fixture.inventory.syncFailure = failureMode;
        } else {
            fixture.inventory.swapFailure = failureMode;
        }

        AutoToolSwapRoundResult failed = fixture.service.handleIntent(fixture.player, fixture.endpoint, intent,
                fixture.inventory, 2L);
        Assert.assertEquals(AutoToolSwapResultCode.SYNC_FAILED, failed.outcome());
        Assert.assertEquals(AutoToolSwapRoundState.ORPHANED, failed.roundState());
        Assert.assertEquals(swapsBefore + 1, fixture.inventory.swapCount);
        Assert.assertEquals(syncsBefore + (failSync ? 1 : 0), fixture.inventory.syncCount);
        Assert.assertEquals(failed, fixture.service.handleIntent(fixture.player, fixture.endpoint, intent,
                fixture.inventory, 3L));
        Assert.assertEquals(swapsBefore + 1, fixture.inventory.swapCount);
        Assert.assertEquals(syncsBefore + (failSync ? 1 : 0), fixture.inventory.syncCount);
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
        return fixture(new AutoToolSwapRoundService(0L));
    }

    private static Fixture fixture(AutoToolSwapRoundService service) {
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

    private static AutoToolSwapIntent currentRestoreIntentForSlots(Fixture fixture, long sequence,
            int anchor, int candidate) {
        return intent(fixture.roundId, sequence, AutoToolSwapAction.RESTORE, anchor, candidate,
                fixture.inventory.slots[anchor], fixture.inventory.slots[candidate]);
    }

    private static AutoToolSwapIntent abandonIntent(Fixture fixture, long sequence) {
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        return new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION, fixture.roundId, sequence,
                AutoToolSwapAction.ABANDON, 0, 9, empty, empty);
    }

    private static void assertNoInventoryAccess(FakeInventory inventory) {
        Assert.assertEquals(0, inventory.readCount);
        Assert.assertEquals(0, inventory.swapCount);
        Assert.assertEquals(0, inventory.syncCount);
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

    private static long activateRound(AutoToolSwapRoundService service) {
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 1L, 0L);
        return service.activatePendingRound(player, endpoint, 1L).serverRoundId();
    }

    private interface InventoryMutation {

        void apply(FakeInventory inventory);
    }

    private enum FailureMode {
        NONE,
        RUNTIME,
        LINKAGE
    }

    private enum TakeoverReasonCase {
        INVENTORY_CONTEXT,
        SELECTED_SLOT,
        PENDING_ANCHOR_CHANGED,
        CANDIDATE_FINGERPRINT,
        CANDIDATE_LOW_RESERVE,
        LEDGER_OLD_ROLE,
        LEDGER_ACTIVE_ROLE,
        SLOT_CONFLICT,
        INVENTORY_READ_FAILED,
        APPLIED,
        SYNC_FAILED
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

    private static final class FakeInventory implements AutoToolSwapInventoryPort,
            AutoToolSwapRoundService.DiagnosticInventory {

        private final AutoToolSwapStackState[] slots = new AutoToolSwapStackState[36];
        private boolean alive = true;
        private boolean creative;
        private boolean window0 = true;
        private boolean cursorEmpty = true;
        private int selectedSlot;
        private boolean failRead;
        private FailureMode swapFailure = FailureMode.NONE;
        private FailureMode syncFailure = FailureMode.NONE;
        private int readCount;
        private int swapCount;
        private int syncCount;
        private int rotateCount;
        private int contextReadCount;
        private int diagnosticCount;

        @Override
        public boolean isPlayerAlive() {
            contextReadCount++;
            return alive;
        }

        @Override
        public boolean isCreativeMode() {
            contextReadCount++;
            return creative;
        }

        @Override
        public boolean hasPersonalInventoryWindow0() {
            contextReadCount++;
            return window0;
        }

        @Override
        public boolean isCursorEmpty() {
            contextReadCount++;
            return cursorEmpty;
        }

        @Override
        public int selectedHotbarSlot() {
            contextReadCount++;
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
            throwForFailure(swapFailure, "swap outcome unknown");
            AutoToolSwapStackState value = slots[anchorSlot];
            slots[anchorSlot] = slots[candidateSlot];
            slots[candidateSlot] = value;
        }

        @Override
        public void rotateInventorySlotsAtomically(int anchorSlot, int oldCandidateSlot, int newCandidateSlot) {
            rotateCount++;
            AutoToolSwapStackState anchor = slots[anchorSlot];
            AutoToolSwapStackState oldCandidate = slots[oldCandidateSlot];
            slots[anchorSlot] = slots[newCandidateSlot];
            slots[oldCandidateSlot] = anchor;
            slots[newCandidateSlot] = oldCandidate;
        }

        @Override
        public void syncInventoryDifference() {
            syncCount++;
            throwForFailure(syncFailure, "sync failure");
        }

        @Override
        public AutoToolSwapRoundService.InventoryDiagnosticSnapshot captureDiagnosticSnapshot(
                int anchorSlot, int candidateSlot) {
            diagnosticCount++;
            return new AutoToolSwapRoundService.InventoryDiagnosticSnapshot(selectedSlot,
                    "registry=mod:anchor,contentHash=short",
                    "registry=mod:candidate,contentHash=short",
                    "registry=mod:current,contentHash=short");
        }

        private static void throwForFailure(FailureMode failureMode, String message) {
            if (failureMode == FailureMode.RUNTIME) {
                throw new IllegalStateException(message);
            }
            if (failureMode == FailureMode.LINKAGE) {
                throw new LinkageError(message);
            }
        }
    }
}
