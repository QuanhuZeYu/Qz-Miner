package club.heiqi.qz_miner.parallel;

/**
 * 单个任务分片的工作量预算。
 */
public final class ParallelWorkBudget {

    private final int initialUnits;
    private int remainingUnits;

    public ParallelWorkBudget(int initialUnits) {
        this.initialUnits = Math.max(1, initialUnits);
        this.remainingUnits = this.initialUnits;
    }

    /**
     * 尝试消耗预算。
     *
     * @param units 工作单位数
     * @return 预算足够时返回 true
     */
    public boolean tryConsumeWork(int units) {
        if (units <= 0) {
            return true;
        }
        if (remainingUnits < units) {
            return false;
        }
        remainingUnits -= units;
        return true;
    }

    /**
     * @return 是否还有剩余预算
     */
    public boolean hasRemaining() {
        return remainingUnits > 0;
    }

    /**
     * @return 初始预算单位数
     */
    public int getInitialUnits() {
        return initialUnits;
    }

    /**
     * @return 剩余预算单位数
     */
    public int getRemainingUnits() {
        return remainingUnits;
    }
}
