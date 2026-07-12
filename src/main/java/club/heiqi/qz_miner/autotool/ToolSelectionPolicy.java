package club.heiqi.qz_miner.autotool;

import java.util.List;

/** 对 mainInventory 0..35 的纯逻辑确定性工具选择。 */
public final class ToolSelectionPolicy {
    private ToolSelectionPolicy() { }

    /** 候选工具的已计算事实，不持有 ItemStack 或世界对象。 */
    public interface Candidate {
        int slot();
        boolean canHarvest();
        int silkTouchLevel();
        int fortuneLevel();
        double baseSpeed();
        int efficiencyLevel();
        int remainingDurability();
    }

    /**
     * @param candidates mainInventory 候选事实
     * @param currentSlot 当前槽位
     * @param minimumReserve 最低采掘后耐久储备
     * @return 最优槽位；无合法候选返回 -1
     */
    public static int select(List<? extends Candidate> candidates, int currentSlot, int minimumReserve) {
        Candidate current = find(candidates, currentSlot);
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate.slot() < 0 || candidate.slot() > 35 || !candidate.canHarvest()
                    || candidate.remainingDurability() < minimumReserve + 1
                    || !preserves(current, candidate)) {
                continue;
            }
            if (best == null || compare(candidate, best) > 0) best = candidate;
        }
        return best == null ? -1 : best.slot();
    }

    private static Candidate find(List<? extends Candidate> candidates, int slot) {
        for (Candidate candidate : candidates) if (candidate.slot() == slot) return candidate;
        return null;
    }

    private static boolean preserves(Candidate current, Candidate candidate) {
        if (current == null) return true;
        if (current.silkTouchLevel() > 0) return candidate.silkTouchLevel() > 0;
        return current.fortuneLevel() <= 0 || candidate.fortuneLevel() >= current.fortuneLevel();
    }

    private static int compare(Candidate left, Candidate right) {
        int speed = Double.compare(speed(left), speed(right));
        if (speed != 0) return speed;
        int durability = Integer.compare(left.remainingDurability(), right.remainingDurability());
        if (durability != 0) return durability;
        return Integer.compare(right.slot(), left.slot());
    }

    private static double speed(Candidate candidate) {
        double speed = candidate.baseSpeed();
        int efficiency = candidate.efficiencyLevel();
        return speed > 1.0D && efficiency > 0 ? speed + efficiency * efficiency + 1.0D : speed;
    }
}
