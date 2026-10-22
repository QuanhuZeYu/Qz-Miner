package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 安全点只观察预算；已开始的候选事务仍由 Context 提交。 */
final class TraversalBudget {
    private TraversalBudget() {}

    static TraversalStepResult observe(ParallelTickControl control) {
        if (control.isCancelRequested()) {
            return TraversalStepResult.TERMINATED;
        }
        return control.shouldYield() ? TraversalStepResult.YIELDED : TraversalStepResult.CONTINUE;
    }

    /** 普通遍历先处理取消与目标上限，再观察 deadline；GT 完整预检不使用此上限短路。 */
    static TraversalStepResult observeWithTargetLimit(ChainSearchContext context, ParallelTickControl control) {
        if (control.isCancelRequested()) {
            return TraversalStepResult.TERMINATED;
        }
        if (context.getConfirmedCount() >= context.getMaxTargets()) {
            return TraversalStepResult.COMPLETED;
        }
        return control.shouldYield() ? TraversalStepResult.YIELDED : TraversalStepResult.CONTINUE;
    }

    static TraversalStepResult rotateFrontier(ChainSearchContext context, ParallelTickControl control) {
        while (!context.getNextFrontier().isEmpty()) {
            if (control.shouldYield()) {
                return PlanningCandidateGate.yieldOrTerminate(control);
            }
            ChainTarget target = context.getNextFrontier().poll();
            if (target != null) {
                context.getCurrentFrontier().add(target);
                context.recordDurableProgress();
            }
        }
        return TraversalStepResult.CONTINUE;
    }
}
