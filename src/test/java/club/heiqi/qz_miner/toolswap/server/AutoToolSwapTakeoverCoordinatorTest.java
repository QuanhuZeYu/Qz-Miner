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
    }

    private static final class RecordingSender implements AutoToolSwapTakeoverCoordinator.RequestSender {
        private final List<AutoToolSwapTakeoverRequest> requests =
                new ArrayList<AutoToolSwapTakeoverRequest>();
        @Override public void send(Object endpoint, AutoToolSwapTakeoverRequest request) { requests.add(request); }
    }

    private static final class FakeInventory implements AutoToolSwapInventoryPort {
        private final AutoToolSwapStackState[] slots = new AutoToolSwapStackState[36];
        @Override public boolean isPlayerAlive() { return true; }
        @Override public boolean isCreativeMode() { return false; }
        @Override public boolean hasPersonalInventoryWindow0() { return true; }
        @Override public boolean isCursorEmpty() { return true; }
        @Override public int selectedHotbarSlot() { return 0; }
        @Override public AutoToolSwapStackState readInventorySlot(int slot) {
            return slots[slot] == null ? AutoToolSwapStackState.empty() : slots[slot];
        }
        @Override public void swapInventorySlotsAtomically(int anchor, int candidate) {
            AutoToolSwapStackState value = slots[anchor]; slots[anchor] = slots[candidate]; slots[candidate] = value;
        }
        @Override public void syncInventoryDifference() {}
    }
}
