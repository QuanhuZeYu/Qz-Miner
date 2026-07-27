package club.heiqi.qz_miner.toolswap.server;

import java.util.Collections;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;

import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCancelled;
import club.heiqi.qz_miner.chain.eventbus.event.WatchdogTimeout;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.toolswap.ToolCandidate;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapContentFingerprint;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapProtocol;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.BatchContext;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.BatchOutcome;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.BatchToken;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CandidateScan;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CandidateSnapshot;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.CloseCause;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.FinalizationStatus;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.PrepareResult;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.ScanStatus;
import club.heiqi.qz_miner.toolswap.server.AutoToolSwapServerBatchService.TargetIdentity;

/** 服务端 ordinary batch 唯一 physical owner 的纯 JVM 合同。 */
public class AutoToolSwapServerBatchServiceTest {

    private static final AutoToolSwapStackState ORIGINAL = stack("mod:anchor", "a", 90);
    private static final AutoToolSwapStackState TOOL_B = stack("mod:tool-b", "b", 50);
    private static final AutoToolSwapStackState TOOL_C = stack("mod:tool-c", "c", 60);
    private static final AutoToolSwapStackState FOREIGN = stack("mod:foreign", "x", 20);

    @Test
    public void roundZeroIsValidAndStaleTokenStopsBeforeScanOrInventoryFactory() {
        Fixture fixture = new Fixture();
        BatchToken first = fixture.begin(0L, 7, 10L);
        Assert.assertNotNull(first);
        Assert.assertEquals(0L, fixture.service.snapshot(fixture.playerId).serverRoundId());

        BatchToken replacement = fixture.begin(0L, 7, 10L);
        Assert.assertNotNull(replacement);
        Assert.assertEquals(PrepareResult.STOP,
                fixture.service.prepareTarget(first, target(1), 10L));
        Assert.assertEquals(0, fixture.source.scanCount);
        Assert.assertEquals(0, fixture.factoryCreates);
    }

    @Test
    public void activeOwnerRejectsDifferentRoundIdentityBeforeScanOrInventoryFactory() {
        Fixture fixture = new Fixture();
        Assert.assertNotNull(fixture.begin(0L, 7, 10L));

        Assert.assertNull("同 generation 的不同 wire round 也不得复用 local owner",
                fixture.begin(99L, 7, 11L));
        Assert.assertEquals(0, fixture.source.scanCount);
        Assert.assertEquals(0, fixture.factoryCreates);
        Assert.assertEquals(0L, fixture.service.snapshot(fixture.playerId).serverRoundId());
    }

    @Test
    public void usableCurrentHandProceedsWithoutMutationPort() {
        Fixture fixture = new Fixture();
        fixture.source.handEligible = true;
        fixture.source.authority = true;

        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(fixture.begin(0L, 1, 1L), target(1), 1L));
        Assert.assertEquals(0, fixture.factoryCreates);
        Assert.assertEquals(0, fixture.inventory.swapCount);
    }

    @Test
    public void twoSlotThreeSlotSegmentAndFinalRestorePreserveRolesAndPublishOnce() {
        Fixture fixture = new Fixture();
        fixture.inventory.slots[0] = ORIGINAL;
        fixture.inventory.slots[9] = TOOL_B;
        fixture.inventory.slots[10] = TOOL_C;
        BatchToken token = fixture.begin(0L, 2, 20L);

        fixture.source.candidateSlot = 9;
        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(token, target(1), 20L));
        Assert.assertSame(TOOL_B, fixture.inventory.slots[0]);
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[9]);

        fixture.source.candidateSlot = 10;
        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(token, target(2), 20L));
        Assert.assertSame(TOOL_C, fixture.inventory.slots[0]);
        Assert.assertSame(TOOL_B, fixture.inventory.slots[9]);
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[10]);

        fixture.source.candidateSlot = -1;
        fixture.source.authority = false;
        Assert.assertEquals(PrepareResult.SKIP_TARGET,
                fixture.service.prepareTarget(token, target(3), 20L));
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[0]);
        Assert.assertSame(TOOL_C, fixture.inventory.slots[10]);

        fixture.source.authority = true;
        fixture.source.candidateSlot = 9;
        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(token, target(4), 20L));
        Assert.assertTrue(fixture.service.snapshot(fixture.playerId).hasLedger());

        AutoToolSwapServerBatchService.BatchEndResult end = fixture.service.endOrdinaryBatch(
                token, BatchOutcome.FINISHED, 20L);
        Assert.assertTrue(end.accepted());
        Assert.assertTrue(end.completionReleased());
        Assert.assertEquals(FinalizationStatus.RESTORED, end.finalization().status());
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[0]);
        Assert.assertSame(TOOL_B, fixture.inventory.slots[9]);
        Assert.assertSame(TOOL_C, fixture.inventory.slots[10]);
        Assert.assertEquals(4, fixture.inventory.swapCount);
        Assert.assertEquals(1, fixture.inventory.rotateCount);
        Assert.assertEquals("同一玩家同一 tick 只允许一次完整 window 0 publication",
                1, fixture.inventory.syncCount);
    }

    @Test
    public void candidateDriftSkipsWithoutMutationAndProtectedThirdLayoutStops() {
        Fixture fixture = new Fixture();
        fixture.inventory.slots[0] = ORIGINAL;
        fixture.inventory.slots[9] = TOOL_B;
        fixture.inventory.slots[10] = TOOL_C;
        BatchToken token = fixture.begin(0L, 1, 4L);

        fixture.source.candidateSlot = 9;
        fixture.source.reportedCandidate = TOOL_C;
        Assert.assertEquals(PrepareResult.SKIP_TARGET,
                fixture.service.prepareTarget(token, target(1), 4L));
        Assert.assertEquals(0, fixture.inventory.swapCount);

        fixture.source.reportedCandidate = null;
        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(token, target(2), 4L));
        fixture.inventory.slots[9] = FOREIGN;
        fixture.source.candidateSlot = 10;
        Assert.assertEquals(PrepareResult.STOP,
                fixture.service.prepareTarget(token, target(3), 4L));
        Assert.assertTrue(fixture.service.snapshot(fixture.playerId).recoveryOnly());
        Assert.assertSame("未知第三布局不得被覆盖", FOREIGN, fixture.inventory.slots[9]);
    }

    @Test
    public void mutationExceptionClassifiesPrePostAndUnknownWithoutReplay() {
        assertMutationFailure(MutationMode.THROW_PRE, PrepareResult.SKIP_TARGET, false, false);
        assertMutationFailure(MutationMode.THROW_POST, PrepareResult.STOP, true, true);
        assertMutationFailure(MutationMode.THROW_UNKNOWN, PrepareResult.STOP, false, true);
    }

    @Test
    public void firstMutationUnknownFinalizesAsConflictWithoutBlindRestore() {
        Fixture fixture = new Fixture();
        fixture.inventory.slots[0] = ORIGINAL;
        fixture.inventory.slots[9] = TOOL_B;
        fixture.inventory.mode = MutationMode.THROW_UNKNOWN;
        fixture.source.candidateSlot = 9;
        BatchToken token = fixture.begin(0L, 1, 13L);

        Assert.assertEquals(PrepareResult.STOP,
                fixture.service.prepareTarget(token, target(1), 13L));
        AutoToolSwapServerBatchService.BatchEndResult end = fixture.service.endOrdinaryBatch(
                token, BatchOutcome.STOPPED, 13L);

        Assert.assertEquals(FinalizationStatus.CONFLICT, end.finalization().status());
        Assert.assertSame("未知首 mutation 不得被伪报为无账本或盲目恢复", FOREIGN,
                fixture.inventory.slots[0]);
        Assert.assertEquals(1, fixture.inventory.swapCount);
    }

    @Test
    public void segmentRestorePreImageFailureSkipsTargetInsteadOfUsingBorrowedTool() {
        Fixture fixture = new Fixture();
        fixture.inventory.slots[0] = ORIGINAL;
        fixture.inventory.slots[9] = TOOL_B;
        fixture.source.candidateSlot = 9;
        BatchToken token = fixture.begin(0L, 1, 14L);
        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(token, target(1), 14L));

        fixture.source.candidateSlot = -1;
        fixture.source.authority = true;
        fixture.inventory.mode = MutationMode.THROW_PRE;
        Assert.assertEquals(PrepareResult.SKIP_TARGET,
                fixture.service.prepareTarget(token, target(2), 14L));
        Assert.assertSame(TOOL_B, fixture.inventory.slots[0]);
        Assert.assertTrue(fixture.service.snapshot(fixture.playerId).hasLedger());
        Assert.assertEquals(2, fixture.inventory.swapCount);
    }

    @Test
    public void continuationThenSameTickKeyReleaseMergesIntoOneFinalPublication() {
        Fixture fixture = swappedFixture(30L);
        BatchToken token = fixture.activeToken;
        Assert.assertTrue(fixture.service.endOrdinaryBatch(token, BatchOutcome.CONTINUE, 30L).accepted());

        AutoToolSwapServerBatchService.FinalizationResult result = fixture.service.finalizePlayer(
                fixture.playerId, fixture.endpoint, null, CloseCause.KEY_RELEASE, 30L);
        Assert.assertEquals(FinalizationStatus.RESTORED, result.status());
        Assert.assertEquals(1, fixture.inventory.syncCount);
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[0]);
        Assert.assertNull(fixture.service.snapshot(fixture.playerId));
    }

    @Test
    public void syncFailureRetriesOnlyPublicationOnNextTick() {
        Fixture fixture = swappedFixture(40L);
        fixture.inventory.failSync = true;
        int mutations = fixture.inventory.swapCount;

        AutoToolSwapServerBatchService.FinalizationResult result = fixture.service.finalizePlayer(
                fixture.playerId, fixture.endpoint, null, CloseCause.NATURAL_FINISH, 40L);
        Assert.assertEquals(FinalizationStatus.RESTORED, result.status());
        Assert.assertTrue(result.publicationAttempted());
        Assert.assertFalse(result.publicationSucceeded());
        Assert.assertEquals(mutations + 1, fixture.inventory.swapCount);
        Assert.assertTrue(fixture.service.snapshot(fixture.playerId).visibilityDirty());

        fixture.inventory.failSync = false;
        Assert.assertEquals(0, fixture.service.flushPublications(40L).attempted());
        Assert.assertEquals(1, fixture.service.flushPublications(41L).succeeded());
        Assert.assertEquals("publication retry 绝不重放 mutation", mutations + 1,
                fixture.inventory.swapCount);
        Assert.assertEquals(2, fixture.inventory.syncCount);
    }

    @Test
    public void allLifecycleFinalizersAreIdempotentAndNeverMutateTwice() {
        for (CloseCause cause : CloseCause.values()) {
            Fixture fixture = swappedFixture(50L);
            int before = fixture.inventory.swapCount;
            AutoToolSwapServerBatchService.FinalizationResult first = fixture.service.finalizePlayer(
                    fixture.playerId, fixture.endpoint, null, cause, 50L);
            AutoToolSwapServerBatchService.FinalizationResult second = fixture.service.finalizePlayer(
                    fixture.playerId, fixture.endpoint, null, cause, 50L);
            Assert.assertEquals(cause.name(), FinalizationStatus.RESTORED, first.status());
            Assert.assertEquals(cause.name(), FinalizationStatus.ALREADY_RESTORED, second.status());
            Assert.assertEquals(cause.name(), before + 1, fixture.inventory.swapCount);
        }
    }

    @Test
    public void cloneCanRestoreThroughAlternateMatchingEndpoint() {
        Fixture fixture = swappedFixture(60L);
        Object newEndpoint = new Object();
        FakeInventory cloneInventory = fixture.inventory.copy();
        fixture.inventory.slots[0] = FOREIGN;
        fixture.inventories.put(newEndpoint, cloneInventory);

        AutoToolSwapServerBatchService.FinalizationResult result = fixture.service.finalizePlayer(
                fixture.playerId, fixture.endpoint, newEndpoint, CloseCause.CLONE, 60L);
        Assert.assertEquals(FinalizationStatus.RESTORED, result.status());
        Assert.assertSame(ORIGINAL, cloneInventory.slots[0]);
        Assert.assertEquals(1, cloneInventory.syncCount);
        Assert.assertSame(FOREIGN, fixture.inventory.slots[0]);
    }

    @Test
    public void lifecycleSubscriptionRejectsStaleIdentityButForcedCleanupRestoresCurrentOwner() {
        Fixture fixture = new Fixture();
        fixture.inventory.slots[0] = ORIGINAL;
        fixture.inventory.slots[9] = TOOL_B;
        fixture.source.candidateSlot = 9;
        BatchToken token = fixture.begin(7L, 3, 70L);
        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(token, target(1), 70L));
        ChainEventBus bus = new ChainEventBus();
        fixture.service.subscribeLifecycle(bus);

        bus.publish(new PlanCancelled(fixture.playerId, 6L, 3, 70L, 0L, "stale-round"));
        bus.publish(new WatchdogTimeout(fixture.playerId, 7L, 2, 70L, 0L, 1L));
        bus.drain();
        Assert.assertTrue("旧 round/generation 事件不得清当前 owner",
                fixture.service.snapshot(fixture.playerId).hasLedger());
        Assert.assertEquals(1, fixture.inventory.swapCount);

        bus.publish(new LifecycleCleanup(fixture.playerId, 0L, 0, 70L, 0L,
                "forced-lifecycle", true, false));
        bus.drain();
        Assert.assertNull(fixture.service.snapshot(fixture.playerId));
        Assert.assertSame(ORIGINAL, fixture.inventory.slots[0]);
        Assert.assertEquals(2, fixture.inventory.swapCount);
    }

    private static void assertMutationFailure(MutationMode mode, PrepareResult expected,
            boolean ledger, boolean dirty) {
        Fixture fixture = new Fixture();
        fixture.inventory.slots[0] = ORIGINAL;
        fixture.inventory.slots[9] = TOOL_B;
        fixture.inventory.mode = mode;
        fixture.source.candidateSlot = 9;
        PrepareResult result = fixture.service.prepareTarget(fixture.begin(0L, 1, 12L), target(1), 12L);

        Assert.assertEquals(mode.name(), expected, result);
        Assert.assertEquals(mode.name(), 1, fixture.inventory.swapCount);
        Assert.assertEquals(mode.name(), ledger, fixture.service.snapshot(fixture.playerId).hasLedger());
        Assert.assertEquals(mode.name(), dirty, fixture.service.snapshot(fixture.playerId).visibilityDirty());
    }

    private static Fixture swappedFixture(long tick) {
        Fixture fixture = new Fixture();
        fixture.inventory.slots[0] = ORIGINAL;
        fixture.inventory.slots[9] = TOOL_B;
        fixture.source.candidateSlot = 9;
        fixture.activeToken = fixture.begin(0L, 1, tick);
        Assert.assertEquals(PrepareResult.PROCEED,
                fixture.service.prepareTarget(fixture.activeToken, target(1), tick));
        return fixture;
    }

    private static ChainTarget target(int x) { return new ChainTarget(x, 64, 0); }

    private static AutoToolSwapStackState stack(String role, String content, int durability) {
        return AutoToolSwapStackState.occupied(role,
                AutoToolSwapContentFingerprint.fromContent(role, content), durability);
    }

    private enum MutationMode { NORMAL, THROW_PRE, THROW_POST, THROW_UNKNOWN }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final Object endpoint = new Object();
        private final Object world = new Object();
        private final java.util.Map<Object, FakeInventory> inventories =
                new java.util.HashMap<Object, FakeInventory>();
        private final FakeInventory inventory = new FakeInventory();
        private final FakeSource source = new FakeSource(world, inventory);
        private int factoryCreates;
        private BatchToken activeToken;
        private final AutoToolSwapServerBatchService service = new AutoToolSwapServerBatchService(source,
                new AutoToolSwapServerBatchService.InventoryFactory() {
                    @Override
                    public AutoToolSwapInventoryPort create(Object requestedEndpoint) {
                        factoryCreates++;
                        return inventories.get(requestedEndpoint);
                    }
                });

        private Fixture() {
            inventories.put(endpoint, inventory);
            service.publishPolicy(1L, true, Collections.emptyList());
        }

        private BatchToken begin(long roundId, int generation, long tick) {
            return service.beginOrdinaryBatch(new BatchContext(playerId, endpoint, world, 0,
                    generation, roundId, 0), tick);
        }
    }

    private static final class FakeSource implements AutoToolSwapServerBatchService.CandidateSource {
        private final Object world;
        private final FakeInventory inventory;
        private int candidateSlot = -1;
        private AutoToolSwapStackState reportedCandidate;
        private boolean handEligible;
        private boolean authority = true;
        private boolean targetMatches = true;
        private ScanStatus status = ScanStatus.READY;
        private int scanCount;

        private FakeSource(Object world, FakeInventory inventory) {
            this.world = world;
            this.inventory = inventory;
        }

        @Override
        public CandidateScan scan(Object endpoint, ChainTarget target,
                java.util.List<club.heiqi.qz_miner.toolswap.ToolSelector> selectors) {
            scanCount++;
            if (status != ScanStatus.READY) return CandidateScan.classified(status, world, 0, 0);
            java.util.List<CandidateSnapshot> candidates = new java.util.ArrayList<CandidateSnapshot>();
            if (candidateSlot >= 0) {
                AutoToolSwapStackState state = reportedCandidate == null
                        ? inventory.slots[candidateSlot] : reportedCandidate;
                candidates.add(new CandidateSnapshot(new ToolCandidate(candidateSlot,
                        "mod:tool-" + candidateSlot, 0, Collections.<String>emptyList(),
                        true, true, state.remainingDurability()), state));
            }
            return CandidateScan.ready(world, 0, 0,
                    new TargetIdentity(1 + target.getX(), 0), inventory.slots[0],
                    handEligible, candidates);
        }

        @Override
        public boolean targetStillMatches(Object endpoint, ChainTarget target, TargetIdentity identity) {
            return targetMatches && identity.blockId() == 1 + target.getX();
        }

        @Override
        public boolean canHarvest(Object endpoint, ChainTarget target) { return authority; }
    }

    private static final class FakeInventory implements AutoToolSwapInventoryPort {
        private final AutoToolSwapStackState[] slots =
                new AutoToolSwapStackState[AutoToolSwapProtocol.INVENTORY_SLOT_COUNT];
        private MutationMode mode = MutationMode.NORMAL;
        private boolean failSync;
        private int swapCount;
        private int rotateCount;
        private int syncCount;

        private FakeInventory() {
            java.util.Arrays.fill(slots, AutoToolSwapStackState.empty());
        }

        private FakeInventory copy() {
            FakeInventory copy = new FakeInventory();
            System.arraycopy(slots, 0, copy.slots, 0, slots.length);
            return copy;
        }

        @Override public boolean isPlayerAlive() { return true; }
        @Override public boolean isCreativeMode() { return false; }
        @Override public boolean hasPersonalInventoryWindow0() { return true; }
        @Override public boolean isCursorEmpty() { return true; }
        @Override public int selectedHotbarSlot() { return 0; }
        @Override public AutoToolSwapStackState readInventorySlot(int slot) { return slots[slot]; }

        @Override
        public void swapInventorySlotsAtomically(int first, int second) {
            swapCount++;
            if (mode == MutationMode.THROW_PRE) throw new IllegalStateException("pre");
            if (mode == MutationMode.THROW_UNKNOWN) {
                slots[first] = FOREIGN;
                throw new IllegalStateException("unknown");
            }
            AutoToolSwapStackState value = slots[first];
            slots[first] = slots[second];
            slots[second] = value;
            if (mode == MutationMode.THROW_POST) throw new IllegalStateException("post");
        }

        @Override
        public void rotateInventorySlotsAtomically(int anchor, int oldCarrier, int nextCarrier) {
            rotateCount++;
            AutoToolSwapStackState anchorValue = slots[anchor];
            AutoToolSwapStackState oldValue = slots[oldCarrier];
            slots[anchor] = slots[nextCarrier];
            slots[oldCarrier] = anchorValue;
            slots[nextCarrier] = oldValue;
        }

        @Override
        public void syncInventoryDifference() {
            syncCount++;
            if (failSync) throw new IllegalStateException("sync");
        }
    }
}
