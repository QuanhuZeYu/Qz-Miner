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
    @Test public void stabilizerHandlesChangeEmptyFreezeAndReset() {
        TargetStabilizer<String> s = new TargetStabilizer<String>(2, 2);
        Assert.assertEquals("a", s.update("a")); Assert.assertEquals("a", s.update("b"));
        Assert.assertEquals("b", s.update("b")); Assert.assertEquals("b", s.update(null)); Assert.assertEquals("b", s.update(null));
        Assert.assertNull(s.update(null)); s.update("c"); s.freeze(true); Assert.assertEquals("c", s.update("d"));
        s.reset(); Assert.assertNull(s.current());
    }
    @Test public void transactionRestoresIdempotentlyAndRejectsConflict() {
        Object[] values = {new Object(), new Object()}; Slots slots = new Slots(values);
        ToolSwapTransaction.Start<Object> start = ToolSwapTransaction.begin(slots, 0, 1);
        Assert.assertEquals(ToolSwapTransaction.Result.SWAPPED, start.result);
        Assert.assertEquals(ToolSwapTransaction.Result.RESTORED, start.transaction.restore(slots));
        Assert.assertEquals(ToolSwapTransaction.Result.ALREADY_RESTORED, start.transaction.restore(slots));
        ToolSwapTransaction.Start<Object> second = ToolSwapTransaction.begin(slots, 0, 1);
        values[0] = new Object();
        Assert.assertEquals(ToolSwapTransaction.Result.CONFLICT, second.transaction.restore(slots));
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
    private static final class Slots implements ToolSwapTransaction.Slots<Object> {
        final Object[] values; Slots(Object[] values){this.values=values;} public Object get(int slot){return values[slot];}
        public void swap(int a,int b){Object value=values[a];values[a]=values[b];values[b]=value;}
    }
}
