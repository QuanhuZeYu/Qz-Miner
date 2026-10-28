package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 累计瞄准目标连续稳定 tick，供控制器在同一语义身份内接受新目标。
 * 首次预览与世界、模式等语义变化由调用方直接换代，回到当前目标或生命周期清理时 reset。
 */
final class PreviewTargetDebounce {

    private ChainTarget candidate;
    private int stableTicks;

    /**
     * 累计候选目标并判断是否已稳定。
     *
     * @param target 本 tick 的瞄准目标
     * @param requiredTicks 需要的连续稳定 tick 数；&lt;= 0 / 1 表示立即接受
     * @return 是否接受该目标
     */
    boolean shouldAccept(ChainTarget target, int requiredTicks) {
        if (target == null) {
            reset();
            return false;
        }
        if (requiredTicks <= 1) {
            candidate = target;
            stableTicks = 1;
            return true;
        }
        if (candidate == null || !candidate.equals(target)) {
            candidate = target;
            stableTicks = 1;
            return false;
        }
        stableTicks++;
        return stableTicks >= requiredTicks;
    }

    /** 结算抖动窗口（目标与当前代一致 / 新代启动 / 生命周期清理）。 */
    void reset() {
        candidate = null;
        stableTicks = 0;
    }

    /** @return 当前候选目标；无候选为 null */
    ChainTarget getCandidate() {
        return candidate;
    }

    /** @return 当前连续稳定 tick 数 */
    int getStableTicks() {
        return stableTicks;
    }
}
