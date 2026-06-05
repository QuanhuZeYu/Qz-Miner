package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 连锁遍历协议工具。
 */
public final class ChainTraversalSupport {

    private ChainTraversalSupport() {}

    /**
     * 执行一次预算化遍历分片。
     */
    public static TraversalStepResult step(
        BudgetedChainTraverser traverser,
        ChainSearchContext context,
        ParallelTickControl control,
        ChainTargetMatcher matcher,
        ChainTargetConsumer consumer) {
        if (traverser == null || context == null || control == null || matcher == null || consumer == null) {
            return TraversalStepResult.COMPLETED;
        }

        if (control.isCancelRequested()) {
            return TraversalStepResult.TERMINATED;
        }

        if (control.shouldYield()) {
            return TraversalStepResult.YIELDED;
        }

        return traverser.step(context, control, matcher, consumer);
    }

    /**
     * 将遍历分片结果映射为并行任务分片结果。
     */
    public static ParallelTaskResult toParallelTaskResult(TraversalStepResult result) {
        switch (result) {
            case CONTINUE:
                return ParallelTaskResult.CONTINUE;
            case YIELDED:
                return ParallelTaskResult.YIELDED;
            case COMPLETED:
                return ParallelTaskResult.COMPLETED;
            case TERMINATED:
            default:
                return ParallelTaskResult.TERMINATED;
        }
    }
}
