package club.heiqi.qz_miner.toolswap;

/**
 * 自动工具与连锁执行共享的纯值可用性策略。
 */
public final class AutoToolUsabilityPolicy {

    /** 工具继续参与换位或执行所需的最小剩余耐久。 */
    public static final int MIN_REMAINING_DURABILITY = 2;

    private AutoToolUsabilityPolicy() {
    }

    /**
     * @param remainingDurability 剩余耐久；不可损耗物品使用 {@link Integer#MAX_VALUE}
     * @return 是否保留了最后一点耐久
     */
    public static boolean hasDurabilityReserve(int remainingDurability) {
        return remainingDurability >= MIN_REMAINING_DURABILITY;
    }

    /**
     * @param effective 对目标是否有实际采掘效率
     * @param canHarvest 是否满足目标收获等级
     * @param remainingDurability 剩余耐久；不可损耗物品使用 {@link Integer#MAX_VALUE}
     * @return 工具是否具备继续参与当前动作的完整能力
     */
    public static boolean canContinue(boolean effective, boolean canHarvest, int remainingDurability) {
        return effective && canHarvest && hasDurabilityReserve(remainingDurability);
    }
}
