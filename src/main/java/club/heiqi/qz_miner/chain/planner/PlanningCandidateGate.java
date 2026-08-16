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
