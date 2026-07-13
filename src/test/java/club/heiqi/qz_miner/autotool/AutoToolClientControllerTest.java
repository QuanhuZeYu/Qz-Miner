package club.heiqi.qz_miner.autotool;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** 客户端自动工具适配器的 headless 回归。 */
public class AutoToolClientControllerTest {
    @Test public void selectsAndRestoresHotbarOnlyWhenExpectedSlotStillMatches() {
        Fixture f = new Fixture(); f.add(2, 10.0D); f.pressAndStabilize();
        Assert.assertEquals(2, f.facade.current);
        f.controller.onChainKeyChanged(false);
        Assert.assertEquals(0, f.facade.current);
        f.controller.onChainKeyChanged(true); f.controller.tick();
        f.facade.current = 4;
        f.controller.onChainKeyChanged(false);
        Assert.assertEquals(4, f.facade.current);
    }

    @Test public void inventorySelectionRestoreAndBridgeConfirmAreCoordinated() {
        Fixture f = new Fixture(); f.add(12, 10.0D); f.pressAndStabilize();
        Assert.assertEquals(1, f.bridge.selects);
        f.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        f.controller.onChainKeyChanged(false);
        Assert.assertEquals(1, f.bridge.restores);
        f.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.IDLE);
    }

    @Test public void manualOverrideNonBreakAndInvalidContextStopSelection() {
        Fixture manual = new Fixture(); manual.add(2, 10.0D); manual.pressAndStabilize();
        manual.facade.current = 5; manual.controller.tick();
        Assert.assertEquals(5, manual.facade.current);
        Fixture nonBreak = new Fixture(); nonBreak.add(2, 10.0D); nonBreak.facade.breakMode = false; nonBreak.pressAndStabilize();
        Assert.assertEquals(0, nonBreak.facade.current);
        Fixture invalid = new Fixture(); invalid.add(2, 10.0D); invalid.facade.valid = false; invalid.pressAndStabilize();
        Assert.assertEquals(0, invalid.facade.current);
    }

    @Test public void lockedPhaseFreezesSelectionUntilArmed() {
        Fixture f = new Fixture(); f.add(2, 10.0D); f.facade.phase = AutoToolCoordinator.Phase.LOCKED; f.pressAndStabilize();
        Assert.assertEquals(0, f.facade.current);
        f.facade.phase = AutoToolCoordinator.Phase.ARMED; f.controller.tick(); f.controller.tick();
        Assert.assertEquals(2, f.facade.current);
    }

    @Test public void rejectedAndResyncedBridgePauseCurrentKeyCycle() {
        Fixture f = new Fixture(); f.add(12, 10.0D); f.pressAndStabilize();
        f.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.PAUSED);
        f.controller.tick();
        Assert.assertEquals(1, f.bridge.selects);
        f.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.IDLE);
        f.controller.onChainKeyChanged(false); f.controller.onChainKeyChanged(true); f.controller.tick();
        Assert.assertEquals(2, f.bridge.selects);
    }

    @Test public void lifecycleResetClearsWithoutRestore() {
        Fixture f = new Fixture(); f.add(12, 10.0D); f.pressAndStabilize();
        f.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        f.controller.resetLifecycle();
        Assert.assertEquals(1, f.bridge.resets);
        Assert.assertEquals(0, f.bridge.restores);
    }

    private static final class Fixture {
        final FakeFacade facade = new FakeFacade(); final FakeBridge bridge = new FakeBridge();
        final AutoToolClientController controller = new AutoToolClientController(facade, bridge);
        void add(int slot, double speed) { facade.candidates.add(new Candidate(slot, speed)); }
        void pressAndStabilize() { controller.onChainKeyChanged(true); controller.tick(); }
    }
    private static final class FakeFacade implements AutoToolClientController.Facade {
        boolean enabled = true, breakMode = true, valid = true; int current;
        AutoToolCoordinator.Phase phase = AutoToolCoordinator.Phase.ARMED; Object target = "block";
        final List<AutoToolCoordinator.Candidate<Object>> candidates = new ArrayList<AutoToolCoordinator.Candidate<Object>>();
        public boolean enabled() { return enabled; }
        public boolean breakBlockMode() { return breakMode; }
        public boolean validInteractionContext() { return valid; }
        public AutoToolCoordinator.Phase phase() { return phase; }
        public Object target() { return target; }
        public List<? extends AutoToolCoordinator.Candidate<Object>> candidates() { return candidates; }
        public int currentHotbarSlot() { return current; }
        public void selectHotbarSlot(int slot) { current = slot; }
        public Object inventoryIdentity(int slot) { return Integer.valueOf(slot); }
        public int minimumDurabilityReserve() { return 0; }
        public int inventoryAnchorHotbarSlot() { return current; }
    }
    private static final class FakeBridge implements AutoToolClientController.BridgePort {
        int selects, restores, resets;
        public boolean beginSwap(int source, int anchor, Object sourceId, Object anchorId, Object desired) { selects++; return true; }
        public boolean requestRestore(Object source, Object anchor, Object desired) { restores++; return true; }
        public void tickTimeout() { }
        public void resetLifecycle() { resets++; }
    }
    private static final class Candidate implements AutoToolCoordinator.Candidate<Object> {
        final int slot; final double speed;
        Candidate(int slot, double speed) { this.slot = slot; this.speed = speed; }
        public int slot() { return slot; }
        public boolean canHarvest() { return true; }
        public int silkTouchLevel() { return 0; }
        public int fortuneLevel() { return 0; }
        public double baseSpeed() { return speed; }
        public int efficiencyLevel() { return 0; }
        public int remainingDurability() { return 100; }
        public int sourceContainerSlot() { return MinecraftToolCandidateFactory.toContainerPlayerSlot(slot); }
        public Object identity() { return Integer.valueOf(slot); }
    }
}
