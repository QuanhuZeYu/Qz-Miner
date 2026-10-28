package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 队首目标的可恢复终裁；consumer 正常返回之后才确认，异常不提前推进阶段。 */
final class TraversalTarget {
    private final boolean recheckFilter;
    private ChainTarget target;
    private Phase phase = Phase.READ_FRONTIER;

    TraversalTarget(boolean recheckFilter) {
        this.recheckFilter = recheckFilter;
    }

    ChainTarget get() {
        return target;
    }

    boolean isEmpty() {
        return target == null;
    }

    boolean isReadyToSubmit() {
        return phase == Phase.SUBMIT;
    }

    boolean isAccepted() {
        return phase == Phase.ACCEPTED;
    }

    void clear() {
        target = null;
        phase = Phase.READ_FRONTIER;
    }

    /** 每次只完成一个阶段，驱动层在阶段之间保留既有预算安全点。 */
    TraversalStepResult advance(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        switch (phase) {
            case READ_FRONTIER:
                TraversalStepResult read = readFrontier(context, control);
                if (read != TraversalStepResult.CONTINUE || recheckFilter || target == null) {
                    return read;
                }
                // 空间扫描沿用队首读取后立即检查 matcher 的安全点，不额外插入阶段观察。
                return match(control, matcher);
            case FILTER:
                return filter(context, control);
            case MATCH:
                return match(control, matcher);
            case SUBMIT:
                return submit(context, control, consumer);
            default:
                return TraversalStepResult.CONTINUE;
        }
    }

    private TraversalStepResult readFrontier(ChainSearchContext context, ParallelTickControl control) {
        PlanningCandidateGate.CommitResult result =
                context.tryCommitPlanningCandidate(control, context.getCurrentFrontier().peek());
        TraversalStepResult stopped = PlanningCandidateGate.commitStep(result, control);
        if (stopped != null) {
            return stopped;
        }
        target = context.getCurrentFrontier().poll();
        if (target == null || result == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
            clear();
        } else {
            phase = recheckFilter ? Phase.FILTER : Phase.MATCH;
        }
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult filter(ChainSearchContext context, ParallelTickControl control) {
        PlanningCandidateGate.FilterResult result = context.tryCommitPlanningCandidateFilter(control, target);
        TraversalStepResult stopped = PlanningCandidateGate.filterStep(result, control);
        if (stopped != null) {
            return stopped;
        }
        if (result == PlanningCandidateGate.FilterResult.REJECTED) {
            clear();
        } else {
            phase = Phase.MATCH;
        }
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult match(ParallelTickControl control, ChainTargetMatcher matcher) {
        if (control.shouldYield()) {
            return PlanningCandidateGate.yieldOrTerminate(control);
        }
        if (matcher.matches(target)) {
            phase = Phase.SUBMIT;
        } else {
            // CHAIN 按冻结采掘能力终裁，拒绝时结束该目标的拓扑扩展。
            clear();
        }
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult submit(ChainSearchContext context, ParallelTickControl control,
            ChainTargetConsumer consumer) {
        if (control.shouldYield()) {
            return PlanningCandidateGate.yieldOrTerminate(control);
        }
        if (control.isCancelRequested()) {
            return TraversalStepResult.TERMINATED;
        }
        consumer.accept(target);
        context.incrementConfirmedCount();
        phase = Phase.ACCEPTED;
        return TraversalStepResult.CONTINUE;
    }

    private enum Phase {
        READ_FRONTIER, FILTER, MATCH, SUBMIT, ACCEPTED
    }
}
