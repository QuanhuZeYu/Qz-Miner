package club.heiqi.qz_miner.toolswap;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/** selector 优先与 0..35 稳定 fallback 合同。 */
public class ToolCandidateOrderTest {

    @Test
    public void emptySelectorsUseStableInventoryOrderAndFilterIneligible() {
        List<ToolCandidate> sorted = ToolCandidateOrder.sort(Arrays.asList(
                candidate(20, "mod:axe", 0, true, true, 8),
                candidate(2, "mod:pick", 0, true, true, 8),
                candidate(1, "mod:broken", 0, true, true, 1),
                candidate(0, "mod:wrong", 0, false, true, 8)),
                Collections.<ToolSelector>emptyList());

        Assert.assertEquals(Arrays.asList(Integer.valueOf(2), Integer.valueOf(20)), slots(sorted));
    }

    @Test
    public void earliestSelectorWinsAndEachBucketUsesSlotOrder() {
        List<ToolCandidate> sorted = ToolCandidateOrder.sort(Arrays.asList(
                candidate(3, "mod:drill", 4, true, true, 8),
                candidate(1, "mod:pick", 0, true, true, 8),
                candidate(7, "mod:drill", 4, true, true, 8),
                candidate(0, "mod:fallback", 0, true, true, 8)),
                Arrays.asList(ToolSelectorParser.parse("mod:drill@4"),
                        ToolSelectorParser.parse("ore:toolPickaxe")));

        Assert.assertEquals(Arrays.asList(Integer.valueOf(3), Integer.valueOf(7),
                Integer.valueOf(1), Integer.valueOf(0)), slots(sorted));
    }

    @Test
    public void handAndSwapCandidatesShareTheTwoPointDurabilityReserve() {
        ToolCandidate zero = candidate(0, "mod:zero", 0, true, true, 0);
        ToolCandidate one = candidate(1, "mod:one", 0, true, true, 1);
        ToolCandidate two = candidate(2, "mod:two", 0, true, true, 2);
        ToolCandidate unbreakable = candidate(3, "mod:unbreakable", 0, true, true, Integer.MAX_VALUE);

        Assert.assertFalse(zero.isUsableInHand());
        Assert.assertFalse(one.isUsableInHand());
        Assert.assertFalse(zero.isEligibleForSwap());
        Assert.assertFalse(one.isEligibleForSwap());
        Assert.assertTrue(two.isUsableInHand());
        Assert.assertTrue(two.isEligibleForSwap());
        Assert.assertTrue(unbreakable.isUsableInHand());
        Assert.assertTrue(unbreakable.isEligibleForSwap());
        Assert.assertEquals(2, AutoToolUsabilityPolicy.MIN_REMAINING_DURABILITY);
    }

    private static ToolCandidate candidate(int slot, String id, int subtype,
            boolean effective, boolean harvest, int durability) {
        return new ToolCandidate(slot, id, subtype,
                id.equals("mod:pick") ? Collections.singleton("toolPickaxe")
                        : Collections.<String>emptyList(),
                effective, harvest, durability);
    }

    private static List<Integer> slots(List<ToolCandidate> candidates) {
        java.util.ArrayList<Integer> slots = new java.util.ArrayList<Integer>();
        for (ToolCandidate candidate : candidates) {
            slots.add(Integer.valueOf(candidate.slot()));
        }
        return slots;
    }
}
