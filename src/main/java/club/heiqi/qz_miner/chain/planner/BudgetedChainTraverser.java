package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 可使用并行 Tick 控制对象预算化推进的连锁遍历器。
 */
public interface BudgetedChainTraverser extends ChainTraverser {

    /**
     * 执行一次预算化遍历分片。
     *
     * @param context 搜索上下文
     * @param control 当前并行 Tick 控制对象
     * @param matcher 目标匹配器
     * @param consumer 已确认目标消费者
     * @return 当前分片的结构化结果
     */
    TraversalStepResult step(
        ChainSearchContext context,
        ParallelTickControl control,
        ChainTargetMatcher matcher,
        ChainTargetConsumer consumer);
}
