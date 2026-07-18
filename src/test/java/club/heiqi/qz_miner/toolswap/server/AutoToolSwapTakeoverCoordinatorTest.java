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
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapStackState;
import club.heiqi.qz_miner.toolswap.protocol.AutoToolSwapTakeoverRequest;

/** poll 前协调门的幂等、成功与超时合同。 */
public class AutoToolSwapTakeoverCoordinatorTest {

    @Test
    public void lowToolWaitsWithoutResendingAndAppliedTakeoverProceeds() {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L));
        Assert.assertEquals(1, fixture.sender.requests.size());
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(11L));
        Assert.assertEquals("同一等待门只能下发一次", 1, fixture.sender.requests.size());

        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
        fixture.inventory.slots[7] = candidate;
        AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());
        fixture.service.handleIntent(fixture.player, fixture.endpoint, takeover, fixture.inventory, 12L);

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.beforePoll(13L));
    }

    @Test
    public void timeoutStopsAndUsableToolProceedsWithoutRequest() {
        Fixture timeout = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, timeout.beforePoll(10L));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.STOP, timeout.beforePoll(15L));

        Fixture usable = fixture();
        usable.inventory.slots[0] = stack("tool:held", "ok", 2);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED, usable.beforePoll(10L));
        Assert.assertTrue(usable.sender.requests.isEmpty());
    }

    @Test
    public void emptyHandWaitsWhileCreativeAndAuthorizedUnlimitedToolsProceed() {
        Fixture empty = fixture();
        empty.inventory.slots[0] = AutoToolSwapStackState.empty();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, empty.beforePoll(10L));
        Assert.assertEquals(1, empty.sender.requests.size());

        Fixture creative = fixture();
        creative.inventory.creative = true;
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED, creative.beforePoll(10L));

        Fixture unlimited = fixture();
        unlimited.inventory.slots[0] = stack("tool:unlimited", "stable", Integer.MAX_VALUE);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED, unlimited.beforePoll(10L));
        Assert.assertTrue(creative.sender.requests.isEmpty());
        Assert.assertTrue(unlimited.sender.requests.isEmpty());
    }

    @Test
    public void roundZeroUsesOnlyAuthorityAndNeverReadsInventoryOrSendsRequest() {
        Fixture fixture = fixture();
        fixture.inventory.readCount = 0;
        fixture.inventory.contextReadCount = 0;

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                fixture.beforePoll(0L, 10L, authority(true)));
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                fixture.beforePoll(0L, 11L, authority(false)));
        Assert.assertEquals(0, fixture.inventory.readCount);
        Assert.assertEquals(0, fixture.inventory.contextReadCount);
        Assert.assertTrue(fixture.sender.requests.isEmpty());
    }

    @Test
    public void emptyDeclineRequiresExactSafeEmptyFallbackAndLiveAuthority() {
        Fixture allowed = fixture();
        allowed.inventory.slots[0] = AutoToolSwapStackState.empty();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                allowed.beforePoll(10L, authority(true)));
        AutoToolSwapTakeoverRequest request = allowed.sender.requests.get(0);
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent decline = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                allowed.roundId, request.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER,
                0, 0, empty, empty);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, allowed.service.handleIntent(allowed.player,
                allowed.endpoint, decline, allowed.inventory, 11L).outcome());
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.PROCEED,
                allowed.beforePoll(12L, authority(true)));

        Fixture denied = declinedEmptyFixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                denied.beforePoll(12L, authority(false)));

        Fixture occupied = declinedEmptyFixture();
        occupied.inventory.slots[0] = stack("tool:foreign", "occupied", 10);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                occupied.beforePoll(12L, authority(true)));
    }

    @Test
    public void nonHarvestingUsableHandRequestsCandidateAndAppliedToolIsRevalidated() {
        Fixture fixture = fixture();
        fixture.inventory.slots[0] = stack("tool:held", "enough", 20);
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L, authority(false)));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
        fixture.inventory.slots[7] = candidate;
        AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());
        Assert.assertEquals(AutoToolSwapResultCode.APPLIED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, takeover, fixture.inventory, 11L).outcome());
        Assert.assertEquals("错误候选换入后必须在 poll 前停止",
                AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                fixture.beforePoll(12L, authority(false)));
    }

    @Test
    public void authorityRuntimeAndLinkageFailuresAreFailClosed() {
        Fixture runtime = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                runtime.beforePoll(10L, failingAuthority(false)));
        Fixture linkage = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                linkage.beforePoll(10L, failingAuthority(true)));
        Assert.assertTrue(runtime.sender.requests.isEmpty());
        Assert.assertTrue(linkage.sender.requests.isEmpty());
    }

    @Test
    public void unsafeInventoryAndReadFailuresStopEvenForCreativeOrEmptyHands() {
        for (int variation = 0; variation < 6; variation++) {
            Fixture fixture = fixture();
            fixture.inventory.slots[0] = AutoToolSwapStackState.empty();
            fixture.inventory.creative = true;
            if (variation == 0) fixture.inventory.alive = false;
            if (variation == 1) fixture.inventory.personalWindow = false;
            if (variation == 2) fixture.inventory.cursorEmpty = false;
            if (variation == 3) fixture.inventory.returnNull = true;
            if (variation == 4) fixture.inventory.readFailure = new IllegalStateException("read");
            if (variation == 5) fixture.inventory.readLinkageFailure = true;
            Assert.assertEquals("variation=" + variation,
                    AutoToolSwapTakeoverCoordinator.GateResult.STOP, fixture.beforePoll(10L));
            Assert.assertTrue(fixture.sender.requests.isEmpty());
        }
    }

    @Test
    public void everyEndpointOrTargetIdentityDriftStopsWaitingAndAppliedGates() {
        for (int variation = 0; variation < 8; variation++) {
            assertIdentityDriftStops(variation, false);
            assertIdentityDriftStops(variation, true);
        }
    }

    @Test
    public void sendFailureClosesPendingSoLateTakeoverIsRejected() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        service.observeChainPhase(player, endpoint, roundId, true, false);
        FakeInventory inventory = new FakeInventory();
        inventory.slots[0] = stack("tool:held", "low", 1);
        inventory.slots[7] = stack("tool:next", "fresh", 20);
        AutoToolSwapTakeoverCoordinator coordinator = new AutoToolSwapTakeoverCoordinator(service,
                new AutoToolSwapTakeoverCoordinator.RequestSender() {
                    @Override
                    public void send(Object ignoredEndpoint, AutoToolSwapTakeoverRequest ignoredRequest) {
                        throw new IllegalStateException("send failed");
                    }
                });

        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                coordinator.beforePoll(player, endpoint, roundId, 3, 1, 64, 2,
                        1, 0, inventory, 10L, 5));
        AutoToolSwapIntent late = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                roundId, 1L, AutoToolSwapAction.TAKEOVER, 0, 7,
                inventory.slots[0].contentFingerprint(), inventory.slots[7].contentFingerprint());
        Assert.assertEquals(AutoToolSwapResultCode.REJECTED,
                service.handleIntent(player, endpoint, late, inventory, 11L).outcome());
        Assert.assertEquals(0, inventory.swapCount);
    }

    private static void assertIdentityDriftStops(int variation, boolean applyFirst) {
        Fixture fixture = fixture();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT, fixture.beforePoll(10L));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        if (applyFirst) {
            AutoToolSwapStackState candidate = stack("tool:next", "fresh", 20);
            fixture.inventory.slots[7] = candidate;
            AutoToolSwapIntent takeover = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                    fixture.roundId, request.actionSequence(), AutoToolSwapAction.TAKEOVER, 0, 7,
                    fixture.inventory.slots[0].contentFingerprint(), candidate.contentFingerprint());
            Assert.assertEquals(AutoToolSwapResultCode.APPLIED, fixture.service.handleIntent(fixture.player,
                    fixture.endpoint, takeover, fixture.inventory, 11L).outcome());
        }

        Object endpoint = variation == 0 ? new Object() : fixture.endpoint;
        long roundId = variation == 1 ? fixture.roundId + 1L : fixture.roundId;
        int generation = variation == 2 ? 4 : 3;
        int targetX = variation == 3 ? 2 : 1;
        int targetY = variation == 4 ? 65 : 64;
        int targetZ = variation == 5 ? 3 : 2;
        int blockId = variation == 6 ? 2 : 1;
        int metadata = variation == 7 ? 1 : 0;

        Assert.assertEquals("identity variation=" + variation + " applied=" + applyFirst,
                AutoToolSwapTakeoverCoordinator.GateResult.STOP,
                fixture.coordinator.beforePoll(fixture.player, endpoint, roundId, generation,
                        targetX, targetY, targetZ, blockId, metadata, fixture.inventory, 12L, 5));
        Assert.assertEquals(1, fixture.sender.requests.size());
        Assert.assertEquals(AutoToolSwapRoundService.TakeoverGateState.STOP,
                fixture.service.takeoverGateState(fixture.player, fixture.endpoint, request, 12L));
    }

    private static Fixture fixture() {
        AutoToolSwapRoundService service = new AutoToolSwapRoundService(0L);
        UUID player = UUID.randomUUID();
        Object endpoint = new Object();
        service.beginRound(player, endpoint, 1L, 0L);
        long roundId = service.activatePendingRound(player, endpoint, 1L).serverRoundId();
        service.observeChainPhase(player, endpoint, roundId, true, false);
        RecordingSender sender = new RecordingSender();
        FakeInventory inventory = new FakeInventory();
        inventory.slots[0] = stack("tool:held", "low", 1);
        return new Fixture(service, new AutoToolSwapTakeoverCoordinator(service, sender), sender,
                inventory, player, endpoint, roundId);
    }

    private static Fixture declinedEmptyFixture() {
        Fixture fixture = fixture();
        fixture.inventory.slots[0] = AutoToolSwapStackState.empty();
        Assert.assertEquals(AutoToolSwapTakeoverCoordinator.GateResult.WAIT,
                fixture.beforePoll(10L, authority(true)));
        AutoToolSwapTakeoverRequest request = fixture.sender.requests.get(0);
        AutoToolSwapContentFingerprint empty = AutoToolSwapContentFingerprint.canonicalEmpty();
        AutoToolSwapIntent decline = new AutoToolSwapIntent(AutoToolSwapProtocol.PROTOCOL_VERSION,
                fixture.roundId, request.actionSequence(), AutoToolSwapAction.DECLINE_TAKEOVER,
                0, 0, empty, empty);
        Assert.assertEquals(AutoToolSwapResultCode.ACCEPTED, fixture.service.handleIntent(fixture.player,
                fixture.endpoint, decline, fixture.inventory, 11L).outcome());
        return fixture;
    }

    private static AutoToolSwapTakeoverCoordinator.HarvestAuthority authority(final boolean result) {
        return new AutoToolSwapTakeoverCoordinator.HarvestAuthority() {
            @Override public boolean canHarvest() { return result; }
        };
    }

    private static AutoToolSwapTakeoverCoordinator.HarvestAuthority failingAuthority(final boolean linkage) {
        return new AutoToolSwapTakeoverCoordinator.HarvestAuthority() {
            @Override public boolean canHarvest() {
                if (linkage) throw new LinkageError("authority");
                throw new IllegalStateException("authority");
            }
        };
    }

    private static AutoToolSwapStackState stack(String role, String content, int remaining) {
        return AutoToolSwapStackState.occupied(role,
                AutoToolSwapContentFingerprint.fromContent(role, content), remaining);
    }

    private static final class Fixture {
        private final AutoToolSwapRoundService service;
        private final AutoToolSwapTakeoverCoordinator coordinator;
        private final RecordingSender sender;
        private final FakeInventory inventory;
        private final UUID player;
        private final Object endpoint;
        private final long roundId;

        private Fixture(AutoToolSwapRoundService service, AutoToolSwapTakeoverCoordinator coordinator,
                RecordingSender sender, FakeInventory inventory, UUID player, Object endpoint, long roundId) {
            this.service = service;
            this.coordinator = coordinator;
            this.sender = sender;
            this.inventory = inventory;
            this.player = player;
            this.endpoint = endpoint;
            this.roundId = roundId;
        }

        private AutoToolSwapTakeoverCoordinator.GateResult beforePoll(long tick) {
            return coordinator.beforePoll(player, endpoint, roundId, 3, 1, 64, 2,
                    1, 0, inventory, tick, 5);
        }

        private AutoToolSwapTakeoverCoordinator.GateResult beforePoll(long tick,
                AutoToolSwapTakeoverCoordinator.HarvestAuthority authority) {
            return beforePoll(roundId, tick, authority);
        }

        private AutoToolSwapTakeoverCoordinator.GateResult beforePoll(long requestedRoundId, long tick,
                AutoToolSwapTakeoverCoordinator.HarvestAuthority authority) {
            return coordinator.beforePoll(player, endpoint, requestedRoundId, 3, 1, 64, 2,
                    1, 0, inventory, tick, 5, authority);
        }
    }

    private static final class RecordingSender implements AutoToolSwapTakeoverCoordinator.RequestSender {
        private final List<AutoToolSwapTakeoverRequest> requests =
                new ArrayList<AutoToolSwapTakeoverRequest>();
        @Override public void send(Object endpoint, AutoToolSwapTakeoverRequest request) { requests.add(request); }
    }

    private static final class FakeInventory implements AutoToolSwapInventoryPort {
        private final AutoToolSwapStackState[] slots = new AutoToolSwapStackState[36];
        private int swapCount;
        private boolean alive = true;
        private boolean creative;
        private boolean personalWindow = true;
        private boolean cursorEmpty = true;
        private boolean returnNull;
        private RuntimeException readFailure;
        private boolean readLinkageFailure;
        private int readCount;
        private int contextReadCount;
        @Override public boolean isPlayerAlive() { contextReadCount++; return alive; }
        @Override public boolean isCreativeMode() { contextReadCount++; return creative; }
        @Override public boolean hasPersonalInventoryWindow0() { contextReadCount++; return personalWindow; }
        @Override public boolean isCursorEmpty() { contextReadCount++; return cursorEmpty; }
        @Override public int selectedHotbarSlot() { contextReadCount++; return 0; }
        @Override public AutoToolSwapStackState readInventorySlot(int slot) {
            readCount++;
            if (readFailure != null) throw readFailure;
            if (readLinkageFailure) throw new NoClassDefFoundError("read");
            if (returnNull) return null;
            return slots[slot] == null ? AutoToolSwapStackState.empty() : slots[slot];
        }
        @Override public void swapInventorySlotsAtomically(int anchor, int candidate) {
            swapCount++;
            AutoToolSwapStackState value = slots[anchor]; slots[anchor] = slots[candidate]; slots[candidate] = value;
        }
        @Override public void syncInventoryDifference() {}
    }
}
