package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * planning 候选读取与状态提交之间的 deadline 事务门。
 *
 * <p>调用方只有在 {@link CommitResult#AIR_COMMITTED} 或
 * {@link CommitResult#NORMAL_COMMITTED} 时才能推进 cursor、visited、frontier 或 queue。
 * deadline 只阻止下一次读取；已经开始的世界读取提交一次后，由调用方在下一安全点让出。</p>
 */
final class PlanningCandidateGate {

    CommitResult tryCommit(
        ParallelTickControl control,
        ChainTarget target,
        CandidateBlockReader blockReader) {
        CommitResult beforeRead = stopResult(control);
        if (beforeRead != null) {
            return beforeRead;
        }

        boolean observedAir = blockReader != null && blockReader.isOriginalAir(target);
        if (control == null || control.isCancelRequested()) {
            return CommitResult.TERMINATED;
        }
        return observedAir ? CommitResult.AIR_COMMITTED : CommitResult.NORMAL_COMMITTED;
    }

    FilterResult tryCommitFilter(
        ParallelTickControl control,
        ChainTarget target,
        ChainCandidateFilter candidateFilter) {
        if (control == null || control.isCancelRequested()) {
            return FilterResult.TERMINATED;
        }
        if (control.shouldYield()) {
            return FilterResult.YIELDED;
        }

        boolean accepted = candidateFilter != null && candidateFilter.canTraverse(target);
        if (control.isCancelRequested()) {
            return FilterResult.TERMINATED;
        }
        return accepted ? FilterResult.ACCEPTED : FilterResult.REJECTED;
    }

    private CommitResult stopResult(ParallelTickControl control) {
        if (control == null || control.isCancelRequested()) {
            return CommitResult.TERMINATED;
        }
        return control.shouldYield() ? CommitResult.YIELDED : null;
    }

    /**
     * 提交结果 → 遍历步骤结论：已提交（AIR/NORMAL）返回 null 表示调用方继续；
     * YIELDED/TERMINATED 返回对应步骤（YIELDED 在真实取消时升格 TERMINATED）。
     * 需要保存 cursor 的调用方在拿到非 null 结论后自行保存（让出/终止才需保存，
     * 避免 lambda 捕获循环变量）。全部 traverser 共用此映射，消除各文件手写的结果分支样板。
     */
    static TraversalStepResult commitStep(CommitResult result, ParallelTickControl control) {
        switch (result) {
            case AIR_COMMITTED:
            case NORMAL_COMMITTED:
                return null;
            case TERMINATED:
                return TraversalStepResult.TERMINATED;
            default:
                return yieldOrTerminate(control);
        }
    }

    /** 过滤结果 → 遍历步骤结论（语义同 {@link #commitStep}）。 */
    static TraversalStepResult filterStep(FilterResult result, ParallelTickControl control) {
        if (result == FilterResult.ACCEPTED || result == FilterResult.REJECTED) {
            return null;
        }
        return result == FilterResult.TERMINATED
                ? TraversalStepResult.TERMINATED : yieldOrTerminate(control);
    }

    /** 让出/终止判定单点：真实取消升格为 TERMINATED，否则 YIELDED。 */
    static TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return control != null && control.isCancelRequested()
                ? TraversalStepResult.TERMINATED : TraversalStepResult.YIELDED;
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

    /** candidate filter 事务的可恢复结果。 */
    enum FilterResult {
        ACCEPTED,
        REJECTED,
        YIELDED,
        TERMINATED
    }
}
