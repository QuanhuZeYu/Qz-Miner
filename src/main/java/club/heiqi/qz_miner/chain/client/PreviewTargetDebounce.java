package club.heiqi.qz_miner.chain.client;

import club.heiqi.qz_miner.chain.planner.ChainTarget;

/**
 * 目标抖动去抖窗口（B1.2 / task-20）。
 *
 * <p>语义：仅当「瞄准目标」在连续 {@code requiredTicks} 个 tick 内保持同一值时，才接受新目标；
 * 目标回到当前代已接受的目标时由调用方 {@link #reset()} 结算。语义维度（世界/模式/子模式/
 * 面/上限/配置/对象组）变化由 {@link PreviewInputSnapshot#differsInSemanticIdentity} 判定，
 * <b>不得</b>进入本窗口。</p>
 *
 * <p>首 tick 相位：无当前预览（快照为 null）时调用方直接启动，不经本窗口——即按键后的首 tick
 * 立即出预览。</p>
 *
 * <p>零分配：只持有目标引用与计数。</p>
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
