package club.heiqi.qz_miner.autotool;

import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** Minecraft 候选适配器的 headless 回归。 */
public class MinecraftToolCandidateFactoryTest {
    @Test public void mapsInventoryIndexesToContainerPlayerSlots() {
        Assert.assertEquals(36, MinecraftToolCandidateFactory.toContainerPlayerSlot(0));
        Assert.assertEquals(44, MinecraftToolCandidateFactory.toContainerPlayerSlot(8));
        Assert.assertEquals(9, MinecraftToolCandidateFactory.toContainerPlayerSlot(9));
        Assert.assertEquals(35, MinecraftToolCandidateFactory.toContainerPlayerSlot(35));
        assertIllegalSlot(-1);
        assertIllegalSlot(36);
    }

    @Test public void convertsAllSlotsAndPreservesCandidateFields() {
        List<MinecraftToolCandidateFactory.CandidateView> views = baselineViews();
        views.set(17, new View(true, 1, 4, 7.5D, 3, 91));
        List<ToolSelectionPolicy.Candidate> candidates = MinecraftToolCandidateFactory.fromViews(views);
        Assert.assertEquals(36, candidates.size());
        for (int slot = 0; slot < 36; slot++) Assert.assertEquals(slot, candidates.get(slot).slot());
        ToolSelectionPolicy.Candidate candidate = candidates.get(17);
        Assert.assertTrue(candidate.canHarvest());
        Assert.assertEquals(1, candidate.silkTouchLevel());
        Assert.assertEquals(4, candidate.fortuneLevel());
        Assert.assertEquals(7.5D, candidate.baseSpeed(), 0.0D);
        Assert.assertEquals(3, candidate.efficiencyLevel());
        Assert.assertEquals(91, candidate.remainingDurability());
    }

    @Test public void degradesOnlyThrowingCandidate() {
        List<MinecraftToolCandidateFactory.CandidateView> views = baselineViews();
        views.set(4, new MinecraftToolCandidateFactory.CandidateView() {
            public boolean canHarvest() { throw new IllegalStateException("mod tool failure"); }
            public int silkTouchLevel() { return 0; }
            public int fortuneLevel() { return 0; }
            public double baseSpeed() { return 0; }
            public int efficiencyLevel() { return 0; }
            public int remainingDurability() { return 0; }
        });
        List<ToolSelectionPolicy.Candidate> candidates = MinecraftToolCandidateFactory.fromViews(views);
        Assert.assertFalse(candidates.get(4).canHarvest());
        Assert.assertEquals(0, candidates.get(4).remainingDurability());
        Assert.assertTrue(candidates.get(5).canHarvest());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsViewsOutsideExactMainInventoryRange() {
        MinecraftToolCandidateFactory.fromViews(baselineViews().subList(0, 35));
    }

    private static List<MinecraftToolCandidateFactory.CandidateView> baselineViews() {
        List<MinecraftToolCandidateFactory.CandidateView> views =
                new ArrayList<MinecraftToolCandidateFactory.CandidateView>();
        for (int slot = 0; slot < 36; slot++) views.add(new View(true, 0, 0, 1.0D, 0, Integer.MAX_VALUE));
        return views;
    }

    private static void assertIllegalSlot(int slot) {
        try {
            MinecraftToolCandidateFactory.toContainerPlayerSlot(slot);
            Assert.fail("expected illegal inventory slot");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("0..35"));
        }
    }

    private static final class View implements MinecraftToolCandidateFactory.CandidateView {
        private final boolean harvest;
        private final int silk;
        private final int fortune;
        private final double speed;
        private final int efficiency;
        private final int durability;

        private View(boolean harvest, int silk, int fortune, double speed, int efficiency, int durability) {
            this.harvest = harvest;
            this.silk = silk;
            this.fortune = fortune;
            this.speed = speed;
            this.efficiency = efficiency;
            this.durability = durability;
        }

        public boolean canHarvest() { return harvest; }
        public int silkTouchLevel() { return silk; }
        public int fortuneLevel() { return fortune; }
        public double baseSpeed() { return speed; }
        public int efficiencyLevel() { return efficiency; }
        public int remainingDurability() { return durability; }
    }
}
