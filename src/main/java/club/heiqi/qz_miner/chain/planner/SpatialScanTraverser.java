package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 空间遍历统一驱动：一批装填后逐目标终裁，拒绝目标不影响后续空间范围。 */
abstract class SpatialScanTraverser implements BudgetedChainTraverser {
    private final TraversalTarget target = new TraversalTarget(false);

    protected final void resetTarget() {
        target.clear();
    }

    @Override
    public final TraversalStepResult step(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (context == null || control == null || matcher == null || consumer == null) {
            return TraversalStepResult.COMPLETED;
        }
        while (true) {
            TraversalStepResult stopped = TraversalBudget.observeWithTargetLimit(context, control);
            if (stopped != TraversalStepResult.CONTINUE) {
                if (stopped == TraversalStepResult.COMPLETED) {
                    target.clear();
                }
                return stopped;
            }
            TraversalStepResult result = advance(context, control, matcher, consumer);
            if (result != TraversalStepResult.CONTINUE) {
                return result;
            }
        }
    }

    private TraversalStepResult advance(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (target.isEmpty() && context.getCurrentFrontier().isEmpty()) {
            TraversalStepResult result = enqueueNextBatch(context, control);
            if (result != TraversalStepResult.CONTINUE) {
                return result;
            }
            if (context.getCurrentFrontier().isEmpty()) {
                return hasMoreWork(context) ? TraversalStepResult.CONTINUE : TraversalStepResult.COMPLETED;
            }
        }
        TraversalStepResult result = target.advance(context, control, matcher, consumer);
        if (target.isAccepted()) {
            target.clear();
        }
        return result;
    }

    protected abstract TraversalStepResult enqueueNextBatch(ChainSearchContext context, ParallelTickControl control);
    protected abstract boolean hasMoreWork(ChainSearchContext context);
}
