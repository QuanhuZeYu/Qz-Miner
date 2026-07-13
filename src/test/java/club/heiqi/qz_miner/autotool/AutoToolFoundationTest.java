package club.heiqi.qz_miner.autotool;

import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;

/** 自动工具阶段 1 纯逻辑回归。 */
public class AutoToolFoundationTest {
    @Test public void policyPreservesFortuneAndUsesSpeedThenSlot() {
        Candidate current = new Candidate(0, true, 0, 3, 2, 0, 20);
        Candidate wrong = new Candidate(1, true, 0, 2, 20, 5, 20);
        Candidate slower = new Candidate(2, true, 0, 3, 4, 2, 20);
        Candidate faster = new Candidate(3, true, 0, 3, 5, 2, 20);
        Assert.assertEquals(3, ToolSelectionPolicy.select(Arrays.asList(current, wrong, slower, faster), 0, 2));
    }
    @Test public void policyRejectsInsufficientDurabilityAndPreservesSilk() {
        Assert.assertEquals(0, ToolSelectionPolicy.select(Arrays.asList(
                new Candidate(0, true, 1, 0, 2, 0, 20), new Candidate(1, true, 0, 4, 10, 0, 2)), 0, 2));
    }
    @Test public void policyRejectsOverflowBoundaryAndInvalidSpeed() {
        Assert.assertEquals(-1, ToolSelectionPolicy.select(Arrays.asList(
                new Candidate(1, true, 0, 0, 1, 0, Integer.MAX_VALUE)), 0, Integer.MAX_VALUE));
        Assert.assertEquals(-1, ToolSelectionPolicy.select(Arrays.asList(
                new Candidate(1, true, 0, 0, Double.NaN, 0, 20),
                new Candidate(2, true, 0, 0, Double.POSITIVE_INFINITY, 0, 20),
                new Candidate(3, true, 0, 0, -1, 0, 20)), 0, 2));
    }
    @Test public void stabilizerHandlesChangeEmptyFreezeAndReset() {
        TargetStabilizer<String> s = new TargetStabilizer<String>(2, 2);
        Assert.assertEquals("a", s.update("a")); Assert.assertEquals("a", s.update("b"));
        Assert.assertEquals("b", s.update("b")); Assert.assertEquals("b", s.update(null)); Assert.assertEquals("b", s.update(null));
        Assert.assertNull(s.update(null)); s.update("c"); s.freeze(true); Assert.assertEquals("c", s.update("d"));
        s.reset(); Assert.assertNull(s.current()); Assert.assertEquals("d", s.update("d"));
    }
    @Test public void stabilizerCountersSaturate() throws Exception {
        TargetStabilizer<String> s = new TargetStabilizer<String>(2, Integer.MAX_VALUE);
        s.update("a");
        setInt(s, "emptyTicks", Integer.MAX_VALUE); s.update(null);
        Assert.assertEquals(Integer.MAX_VALUE, getInt(s, "emptyTicks"));
        s.update("a"); s.update("b"); setInt(s, "pendingTicks", Integer.MAX_VALUE); s.update("b");
        Assert.assertEquals("b", s.current());
    }
    @Test public void transactionSelectRestoreHappyPathAndEmptyAnchor() {
        Object source = new Object(); ToolSwapTransaction<Object> tx = new ToolSwapTransaction<Object>();
        ToolSwapTransaction.Outcome select = tx.beginSelect(12, 4, source, null, source);
        Assert.assertEquals(ToolSwapTransaction.Result.INTENT, select.result);
        Assert.assertEquals(12, select.intent.sourceContainerSlot); Assert.assertEquals(4, select.intent.anchorHotbarIndex);
        tx.bindAction((short) 7);
        Assert.assertEquals(ToolSwapTransaction.Result.ACCEPTED, tx.onConfirmAccepted((short) 7).result);
        Assert.assertEquals(ToolSwapTransaction.State.ACTIVE, tx.state());
        Assert.assertEquals(ToolSwapTransaction.Result.INTENT, tx.beginRestore(source, null).result);
        tx.bindAction((short) 8);
        Assert.assertEquals(ToolSwapTransaction.Result.ACCEPTED, tx.onConfirmAccepted((short) 8).result);
        Assert.assertEquals(ToolSwapTransaction.State.IDLE, tx.state());
    }
    @Test public void transactionRejectsIllegalAndOutOfOrderTransitions() {
        Object source = new Object(); Object anchor = new Object(); ToolSwapTransaction<Object> tx = new ToolSwapTransaction<Object>();
        Assert.assertEquals(ToolSwapTransaction.Result.CONFLICT, tx.beginRestore(source, anchor).result);
        Assert.assertEquals(ToolSwapTransaction.Result.CONFLICT, tx.beginSelect(-1, 0, source, anchor, source).result);
        tx.beginSelect(10, 0, source, anchor, source); tx.bindAction((short) 2);
        Assert.assertEquals(ToolSwapTransaction.Result.IGNORED, tx.onConfirmAccepted((short) 1).result);
        Assert.assertEquals(ToolSwapTransaction.State.WAIT_SELECT_CONFIRM, tx.state());
        tx.onConfirmAccepted((short) 2);
        Assert.assertEquals(ToolSwapTransaction.Result.CONFLICT, tx.beginRestore(new Object(), anchor).result);
        Assert.assertEquals(ToolSwapTransaction.State.ACTIVE, tx.state());
        Assert.assertFalse(tx.releasePending());
        Assert.assertEquals(ToolSwapTransaction.Result.CONFLICT,
                tx.beginRestore(new Object(), anchor, null).result);
        Assert.assertEquals(ToolSwapTransaction.State.ACTIVE, tx.state());
        Assert.assertFalse(tx.releasePending());
    }
    @Test public void rejectedConfirmWaitsForWindowItemsAndPreservesLatestDesired() {
        Object source = new Object(); Object latest = new Object(); ToolSwapTransaction<Object> tx = new ToolSwapTransaction<Object>();
        tx.beginSelect(10, 0, source, null, source); tx.bindAction((short) 2); tx.setLatestDesired(latest);
        Assert.assertEquals(ToolSwapTransaction.Result.REJECTED, tx.onConfirmRejected((short) 2).result);
        Assert.assertEquals(ToolSwapTransaction.State.WAIT_RESYNC, tx.state()); Assert.assertSame(latest, tx.latestDesired());
        Assert.assertEquals(ToolSwapTransaction.Result.CONFLICT,
                tx.beginSelect(11, 0, latest, null, latest).result);
        Assert.assertEquals(ToolSwapTransaction.Result.RESYNCED, tx.onWindowItems().result);
        Assert.assertEquals(ToolSwapTransaction.State.PAUSED, tx.state()); Assert.assertSame(latest, tx.latestDesired());
    }
    @Test public void pendingReleaseTimeoutAndResetAreExplicit() {
        Object source = new Object(); ToolSwapTransaction<Object> tx = new ToolSwapTransaction<Object>();
        tx.beginSelect(10, 0, source, null, source); tx.bindAction((short) 2); tx.setLatestDesired(null);
        Assert.assertTrue(tx.releasePending()); tx.onConfirmRejected((short) 2); tx.onWindowItems();
        Assert.assertEquals(ToolSwapTransaction.State.IDLE, tx.state());
        tx.beginSelect(10, 0, source, null, source);
        Assert.assertEquals(ToolSwapTransaction.Result.PAUSED, tx.timeout().result);
        Assert.assertEquals(ToolSwapTransaction.State.PAUSED, tx.state());
        Assert.assertEquals(ToolSwapTransaction.Result.RESET, tx.reset().result);
        Assert.assertEquals(ToolSwapTransaction.State.IDLE, tx.state()); Assert.assertNull(tx.latestDesired());
    }
    private static final class Candidate implements ToolSelectionPolicy.Candidate {
        final int slot, silk, fortune, efficiency, durability; final boolean harvest; final double speed;
        Candidate(int slot, boolean harvest, int silk, int fortune, double speed, int efficiency, int durability) {
            this.slot=slot; this.harvest=harvest; this.silk=silk; this.fortune=fortune; this.speed=speed;
            this.efficiency=efficiency; this.durability=durability; }
        public int slot(){return slot;} public boolean canHarvest(){return harvest;} public int silkTouchLevel(){return silk;}
        public int fortuneLevel(){return fortune;} public double baseSpeed(){return speed;} public int efficiencyLevel(){return efficiency;}
        public int remainingDurability(){return durability;}
    }
    private static void setInt(Object target, String name, int value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.setInt(target, value);
    }
    private static int getInt(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.getInt(target);
    }
}
