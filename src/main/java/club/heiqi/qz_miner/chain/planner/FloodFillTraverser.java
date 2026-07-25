package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 洪泛预算化遍历器。
 */
public class FloodFillTraverser implements BudgetedChainTraverser {

    private final ChainSearchAlgorithm.BudgetState budgetState = new ChainSearchAlgorithm.BudgetState();

    @Override
    public void seed(ChainSearchContext context) {
        ChainTarget origin = context.getOrigin();
        context.getVisited().add(origin);
        budgetState.beginSeedNeighborGeneration(origin);
    }

    @Override
    public TraversalStepResult step(ChainSearchContext context, ParallelTickControl control, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        return ChainSearchAlgorithm.step(context, control, budgetState, matcher, consumer);
    }
}
