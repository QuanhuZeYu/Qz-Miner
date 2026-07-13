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
        f.facade.identities[0] = f.facade.identities[12];
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
        f.controller.onChainKeyChanged(true);
        f.controller.tick();
        Assert.assertEquals(2, f.bridge.selects);
    }

    @Test public void configuredThresholdsAndReloadResetOnlyTargetHistory() {
        Fixture f = new Fixture(); f.add(2, 10.0D); f.facade.stableTicks = 10; f.pressAndStabilize();
        Assert.assertEquals(2, f.facade.current);
        f.facade.target = "other";
        for (int i = 0; i < 9; i++) f.controller.tick();
        Assert.assertEquals(2, f.facade.current);
        f.facade.stableTicks = 20;
        for (int i = 0; i < 19; i++) f.controller.tick();
        Assert.assertEquals(2, f.facade.current);
    }

    @Test public void deathAndReplacementPlayerResetWithoutLateRestore() {
        Fixture f = new Fixture(); f.add(12, 10.0D); f.pressAndStabilize();
        f.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        f.facade.dead = true; f.controller.tick();
        Assert.assertEquals(1, f.bridge.resets); Assert.assertEquals(0, f.bridge.restores);
        f.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        Assert.assertEquals(0, f.bridge.restores);
        f.facade.dead = false; f.facade.player = new Object(); f.controller.tick();
        Assert.assertEquals(2, f.bridge.resets);
        f.controller.onChainKeyChanged(true); f.controller.tick();
        Assert.assertEquals(2, f.bridge.selects);
    }

    @Test public void hotbarIdentityReplacementPausesButSameObjectMutationDoesNot() {
        Fixture replaced = new Fixture(); Object tool = new Object(); replaced.facade.identities[2] = tool;
        replaced.add(2, 10.0D); replaced.pressAndStabilize();
        replaced.facade.identities[2] = null; replaced.controller.tick();
        Assert.assertEquals(0, replaced.facade.current);
        replaced.controller.tick(); Assert.assertEquals(0, replaced.facade.current);
        Fixture same = new Fixture(); same.facade.identities[2] = tool; same.add(2, 10.0D); same.pressAndStabilize();
        same.controller.tick(); Assert.assertEquals(2, same.facade.current);
    }

    @Test public void confirmedInventoryIdentityDetectsNullAndReplacementButAllowsSameObjectMutation() {
        Fixture discarded = activeInventoryFixture();
        discarded.facade.identities[0] = null;
        discarded.controller.tick();
        Assert.assertEquals(1, discarded.bridge.restores);

        Fixture replaced = activeInventoryFixture();
        replaced.facade.identities[0] = new MutableIdentity();
        replaced.controller.tick();
        Assert.assertEquals(1, replaced.bridge.restores);

        Fixture mutated = activeInventoryFixture();
        MutableIdentity identity = (MutableIdentity) mutated.facade.identities[0];
        identity.damage++;
        mutated.controller.tick();
        Assert.assertEquals(0, mutated.bridge.restores);
    }

    @Test public void rejectedAndResyncedTransactionsClearConfirmedInventoryIdentity() {
        Fixture rejected = activeInventoryFixture();
        rejected.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.PAUSED);
        rejected.facade.identities[0] = new Object();
        rejected.controller.tick();
        Assert.assertEquals(0, rejected.bridge.restores);

        Fixture resynced = activeInventoryFixture();
        resynced.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.IDLE);
        resynced.facade.identities[0] = new Object();
        resynced.controller.tick();
        Assert.assertEquals(0, resynced.bridge.restores);
    }

    @Test public void lateAcceptedStatusAfterResetCannotReintroduceInventoryIdentity() {
        Fixture fixture = activeInventoryFixture();
        fixture.controller.resetLifecycle();
        fixture.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        fixture.facade.identities[0] = new Object();
        fixture.controller.tick();
        Assert.assertEquals(0, fixture.bridge.restores);
    }

    @Test public void restoreFalseKeepsHotbarAndInventoryLayoutsIncludingPending() {
        Fixture hotbar = new Fixture(); hotbar.facade.restore = false; hotbar.add(2, 10.0D); hotbar.pressAndStabilize();
        hotbar.controller.onChainKeyChanged(false);
        Assert.assertEquals(2, hotbar.facade.current); Assert.assertEquals(1, hotbar.bridge.forgets);
        Fixture inventory = new Fixture(); inventory.facade.restore = false; inventory.add(12, 10.0D); inventory.pressAndStabilize();
        inventory.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        inventory.controller.onChainKeyChanged(false);
        Assert.assertEquals(0, inventory.bridge.restores); Assert.assertEquals(1, inventory.bridge.forgets);
        Fixture pending = new Fixture(); pending.facade.restore = false; pending.add(12, 10.0D); pending.pressAndStabilize();
        pending.controller.onChainKeyChanged(false); Assert.assertEquals(0, pending.bridge.forgets);
        pending.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        Assert.assertEquals(1, pending.bridge.forgets); Assert.assertEquals(0, pending.bridge.restores);
    }

    private static final class Fixture {
        final FakeFacade facade = new FakeFacade(); final FakeBridge bridge = new FakeBridge();
        final AutoToolClientController controller = new AutoToolClientController(facade, bridge);
        void add(int slot, double speed) {
            if (facade.identities[slot] == null) facade.identities[slot] = new Object();
            facade.candidates.add(new Candidate(slot, speed, facade.identities[slot]));
        }
        void pressAndStabilize() { controller.onChainKeyChanged(true); controller.tick(); }
    }
    private static Fixture activeInventoryFixture() {
        Fixture fixture = new Fixture();
        MutableIdentity identity = new MutableIdentity();
        fixture.facade.identities[12] = identity;
        fixture.add(12, 10.0D);
        fixture.pressAndStabilize();
        fixture.facade.identities[0] = identity;
        fixture.controller.onStatusChanged(VanillaInventoryTransactionBridge.Status.ACTIVE);
        return fixture;
    }
    private static final class MutableIdentity { int damage; }
    private static final class FakeFacade implements AutoToolClientController.Facade {
        boolean enabled = true, breakMode = true, valid = true, restore = true, dead; int current, stableTicks = 2, graceTicks = 2;
        Object player = new Object(); final Object[] identities = new Object[36];
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
        public Object inventoryIdentity(int slot) { return identities[slot]; }
        public int minimumDurabilityReserve() { return 0; }
        public int inventoryAnchorHotbarSlot() { return current; }
        public int targetStableTicks() { return stableTicks; }
        public int emptyTargetGraceTicks() { return graceTicks; }
        public boolean restoreOriginal() { return restore; }
        public Object playerIdentity() { return player; }
        public boolean playerDead() { return dead; }
    }
    private static final class FakeBridge implements AutoToolClientController.BridgePort {
        int selects, restores, resets, forgets;
        public boolean beginSwap(int source, int anchor, Object sourceId, Object anchorId, Object desired) { selects++; return true; }
        public boolean requestRestore(Object source, Object anchor, Object desired) { restores++; return true; }
        public void tickTimeout() { }
        public void resetLifecycle() { resets++; }
        public void forgetAfterConfirmation() { forgets++; }
    }
    private static final class Candidate implements AutoToolCoordinator.Candidate<Object> {
        final int slot; final double speed; final Object identity;
        Candidate(int slot, double speed, Object identity) { this.slot = slot; this.speed = speed; this.identity = identity; }
        public int slot() { return slot; }
        public boolean canHarvest() { return true; }
        public int silkTouchLevel() { return 0; }
        public int fortuneLevel() { return 0; }
        public double baseSpeed() { return speed; }
        public int efficiencyLevel() { return 0; }
        public int remainingDurability() { return 100; }
        public int sourceContainerSlot() { return MinecraftToolCandidateFactory.toContainerPlayerSlot(slot); }
        public Object identity() { return identity; }
    }
}
