package club.heiqi.qz_miner.chain.planner;

import java.util.Queue;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 邻居或空间坐标的接纳事务；保存已读坐标，filter 恢复时不重读世界。 */
final class TraversalCandidate {
    private ChainTarget pending;

    boolean isPending() {
        return pending != null;
    }

    void clear() {
        pending = null;
    }

    TraversalStepResult read(ChainSearchContext context, ParallelTickControl control, ChainTarget target) {
        PlanningCandidateGate.CommitResult result = context.tryCommitPlanningCandidate(control, target);
        TraversalStepResult stopped = PlanningCandidateGate.commitStep(result, control);
        if (stopped != null) {
            return stopped;
        }
        if (result == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
            context.getVisited().add(target);
        } else {
            pending = target;
        }
        return TraversalStepResult.CONTINUE;
    }

    TraversalStepResult filter(ChainSearchContext context, ParallelTickControl control, Queue<ChainTarget> frontier) {
        PlanningCandidateGate.FilterResult result = context.tryCommitPlanningCandidateFilter(control, pending);
        TraversalStepResult stopped = PlanningCandidateGate.filterStep(result, control);
        if (stopped != null) {
            return stopped;
        }
        ChainTarget committed = pending;
        pending = null;
        if (context.getVisited().add(committed) && result == PlanningCandidateGate.FilterResult.ACCEPTED) {
            frontier.add(committed);
        }
        return TraversalStepResult.CONTINUE;
    }
}
