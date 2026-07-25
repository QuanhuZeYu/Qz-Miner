package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * planning 候选事务的共享计费器。
 *
 * <p>同一搜索上下文累计提交 1024 个原版空气候选才消耗一个正工作单位；
 * 非空气候选仍按一次正常候选事务收费。计费器只保存空气余数，候选游标、
 * frontier 与 visited 的提交均由调用方在 {@link CommitResult} 成功后完成。</p>
 */
final class PlanningCandidateWorkBudget {

    static final int AIR_TARGETS_PER_WORK_UNIT = 1024;

    private int airRemainder;

    /**
     * 尝试提交一个候选事务。
     *
     * <p>控制边界检查必须先于世界读取。返回让出或终止时，本对象不会改变余数，
     * 调用方也不得推进候选状态。</p>
     *
     * @param control 当前并行 Tick 控制对象
     * @param target 当前候选坐标
     * @param blockReader 候选方块读取器
     * @return 可恢复的候选事务结果
     */
    CommitResult tryCommit(
        ParallelTickControl control,
        ChainTarget target,
        CandidateBlockReader blockReader) {
        if (control == null) {
            return CommitResult.TERMINATED;
        }
        if (control.isCancelRequested()) {
            return CommitResult.TERMINATED;
        }
        if (control.shouldYield()) {
            return CommitResult.YIELDED;
        }

        boolean observedAir = blockReader != null && blockReader.isOriginalAir(target);
        if (!observedAir) {
            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            return CommitResult.NORMAL_COMMITTED;
        }

        if (airRemainder == AIR_TARGETS_PER_WORK_UNIT - 1) {
            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            airRemainder = 0;
        } else {
            airRemainder++;
        }
        return CommitResult.AIR_COMMITTED;
    }

    /** @return 当前上下文尚未折算为正预算的空气余数 */
    int getAirRemainder() {
        return airRemainder;
    }

    private CommitResult yieldOrTerminate(ParallelTickControl control) {
        return control.isCancelRequested() ? CommitResult.TERMINATED : CommitResult.YIELDED;
    }

    /** 候选坐标的方块读取边界。 */
    @FunctionalInterface
    interface CandidateBlockReader {
        /** @return 读取当前坐标后，方块身份是否精确等于 {@code Blocks.air} */
        boolean isOriginalAir(ChainTarget target);
    }

    /** 候选事务的可恢复结果。 */
    enum CommitResult {
        AIR_COMMITTED,
        NORMAL_COMMITTED,
        YIELDED,
        TERMINATED
    }
}
