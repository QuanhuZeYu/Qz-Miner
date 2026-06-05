package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTaskResult;
import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 连锁遍历协议适配工具。
 */
public final class ChainTraversalSupport {

    private ChainTraversalSupport() {}

    /**
     * 优先使用预算化遍历接口，旧 traverser 暂时通过 maxNodes 兼容路径运行。
     */
    public static TraversalStepResult step(
        ChainTraverser traverser,
        ChainSearchContext context,
        ParallelTickControl control,
        int maxNodes,
        ChainTargetMatcher matcher,
        ChainTargetConsumer consumer) {
        if (control.isCancelRequested()) {
            return TraversalStepResult.TERMINATED;
        }

        if (traverser instanceof BudgetedChainTraverser) {
            return ((BudgetedChainTraverser) traverser).step(context, control, matcher, consumer);
        }

        if (control.shouldYield()) {
            return TraversalStepResult.YIELDED;
        }

        boolean shouldContinue = traverser.step(context, maxNodes, matcher, consumer);
        if (control.isCancelRequested()) {
            return TraversalStepResult.TERMINATED;
        }
        if (shouldContinue && control.shouldYield()) {
            return TraversalStepResult.YIELDED;
        }
        return shouldContinue ? TraversalStepResult.CONTINUE : TraversalStepResult.COMPLETED;
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
