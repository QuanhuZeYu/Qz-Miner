package club.heiqi.qz_miner.autotool;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

public class AutoToolCoordinatorTest {
    @Test public void firstTargetImmediateAndChangedTargetNeedsTwoTicks() {
        Fixture f = new Fixture();
        Assert.assertEquals(AutoToolCoordinator.CommandType.SELECT_HOTBAR, f.tick("A").type);
        f.coordinator.confirm(f.snapshot);
        Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.tick("B").type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.tick("B").type);
    }

    @Test public void emptyTargetHasTwoTickGraceThenRestores() {
        Fixture f = new Fixture(); f.activate("A");
        Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.tick(null).type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.tick(null).type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.tick(null).type);
    }

    @Test public void nonDefaultTenStableAndTwentyEmptyGraceAreApplied() {
        Fixture f = new Fixture(); f.snapshot.targetStableTicks = 10; f.snapshot.emptyTargetGraceTicks = 20;
        f.activate("A");
        for (int i = 0; i < 9; i++) Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.tick("B").type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.tick("B").type);
        f.coordinator.confirm(f.snapshot); f.coordinator.confirm(f.snapshot);
        for (int i = 0; i < 20; i++) Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.tick(null).type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.tick(null).type);
    }

    @Test public void selectsWholeInventoryAndUsesBridgeCoordinates() {
        Fixture f = new Fixture(); f.snapshot.candidates = Arrays.asList(tool(0, 36, 2), tool(20, 20, 20));
        AutoToolCoordinator.Command command = f.tick("A");
        Assert.assertEquals(AutoToolCoordinator.CommandType.SELECT_INVENTORY, command.type);
        Assert.assertEquals(20, command.slot); Assert.assertEquals(4, command.anchorHotbarSlot);
        f.coordinator.confirm(f.snapshot);
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_INVENTORY, f.release().type);
    }

    @Test public void restoreFirstKeepsOnlyLatestDesiredTarget() {
        Fixture f = new Fixture(); f.activate("A"); f.tick("B");
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.tick("B").type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.tick("C").type);
        Assert.assertEquals("B", f.coordinator.state().latestDesiredTarget());
        f.snapshot.target = "C";
        Assert.assertEquals(AutoToolCoordinator.CommandType.SELECT_HOTBAR, f.coordinator.confirm(f.snapshot).type);
    }

    @Test public void pendingReleaseWaitsForConfirmation() {
        Fixture f = new Fixture(); Assert.assertEquals(AutoToolCoordinator.CommandType.SELECT_HOTBAR, f.tick("A").type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.release().type);
        f.coordinator.confirm(f.snapshot);
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.release().type);
    }

    @Test public void allStopInputsRestore() {
        assertStop("manual"); assertStop("disabled"); assertStop("nonBreak"); assertStop("invalid");
    }

    @Test public void lockedFreezesAndIdleFinishRestores() {
        Fixture f = new Fixture(); f.activate("A"); f.snapshot.phase = AutoToolCoordinator.Phase.LOCKED;
        Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.tick("B").type);
        Assert.assertEquals("A", f.coordinator.state().activeTarget());
        f.snapshot.phase = AutoToolCoordinator.Phase.IDLE;
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.tick("B").type);
    }

    @Test public void rejectionAndTimeoutPauseUntilNewKeyCycle() {
        Fixture f = new Fixture(); f.tick("A");
        Assert.assertEquals(AutoToolCoordinator.CommandType.PAUSE, f.coordinator.rejectOrTimeout().type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.PAUSE, f.tick("A").type);
        f.release();
        f.snapshot.keyHeld = true;
        Assert.assertEquals(AutoToolCoordinator.CommandType.SELECT_HOTBAR, f.tick("A").type);
        f.coordinator.rejectOrTimeout(); f.coordinator.resync();
        Assert.assertEquals(AutoToolCoordinator.CommandType.PAUSE, f.tick("A").type);
    }

    @Test public void lifecycleResetClearsWithoutLateRestore() {
        Fixture f = new Fixture(); f.activate("A"); f.coordinator.resetLifecycle();
        Assert.assertEquals(AutoToolControllerState.Status.IDLE, f.coordinator.state().status());
        f.snapshot.keyHeld = false;
        Assert.assertEquals(AutoToolCoordinator.CommandType.NONE, f.coordinator.tick(f.snapshot).type);
    }

    @Test public void hotbarTargetChangeIsExplicitlyRestoreFirstNotAtomic() {
        Fixture f = new Fixture(); f.activate("A"); f.tick("B");
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.tick("B").type);
        Assert.assertEquals(AutoToolCoordinator.CommandType.SELECT_HOTBAR, f.coordinator.confirm(f.snapshot).type);
    }

    private static void assertStop(String kind) {
        Fixture f = new Fixture(); f.activate("A");
        if ("manual".equals(kind)) f.snapshot.manualOverride = true;
        if ("disabled".equals(kind)) f.snapshot.enabled = false;
        if ("nonBreak".equals(kind)) f.snapshot.breakBlockMode = false;
        if ("invalid".equals(kind)) f.snapshot.validInteractionContext = false;
        Assert.assertEquals(AutoToolCoordinator.CommandType.RESTORE_HOTBAR, f.coordinator.tick(f.snapshot).type);
    }

    private static Candidate tool(final int slot, final int container, final double speed) {
        return new Candidate(slot, container, speed);
    }

    private static final class Fixture {
        final AutoToolCoordinator<String> coordinator = new AutoToolCoordinator<String>();
        final AutoToolCoordinator.Snapshot<String> snapshot = new AutoToolCoordinator.Snapshot<String>();
        Fixture() {
            snapshot.enabled = true; snapshot.keyHeld = true; snapshot.breakBlockMode = true;
            snapshot.validInteractionContext = true; snapshot.phase = AutoToolCoordinator.Phase.ARMED;
            snapshot.currentHotbarSlot = 0; snapshot.inventoryAnchorHotbarSlot = 4;
            snapshot.candidates = Arrays.asList(tool(0, 36, 2), tool(3, 39, 10));
        }
        AutoToolCoordinator.Command tick(String target) { snapshot.target = target; return coordinator.tick(snapshot); }
        void activate(String target) { tick(target); coordinator.confirm(snapshot); }
        AutoToolCoordinator.Command release() { snapshot.keyHeld = false; return coordinator.tick(snapshot); }
    }

    private static final class Candidate implements AutoToolCoordinator.Candidate<String> {
        private final int slot; private final int container; private final double speed;
        Candidate(int slot, int container, double speed) { this.slot = slot; this.container = container; this.speed = speed; }
        public int slot() { return slot; } public int sourceContainerSlot() { return container; }
        public String identity() { return "tool-" + slot; } public boolean canHarvest() { return true; }
        public int silkTouchLevel() { return 0; } public int fortuneLevel() { return 0; }
        public double baseSpeed() { return speed; } public int efficiencyLevel() { return 0; }
        public int remainingDurability() { return 100; }
    }
}
