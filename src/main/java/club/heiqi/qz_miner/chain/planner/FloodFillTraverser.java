package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 洪泛预算化遍历器。
 */
public class FloodFillTraverser implements BudgetedChainTraverser {

    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private final ChainSearchAlgorithm.BudgetState budgetState = new ChainSearchAlgorithm.BudgetState();

    @Override
    public void seed(ChainSearchContext context) {
        ChainTarget origin = context.getOrigin();
        context.getVisited().add(origin);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            ChainTarget neighbor = new ChainTarget(origin.getX() + offset[0], origin.getY() + offset[1], origin.getZ() + offset[2]);
            if (!context.getVisited().add(neighbor)) {
                continue;
            }

            if (!context.canTraverse(neighbor)) {
                continue;
            }

            context.getCurrentFrontier().add(neighbor);
        }
    }

    @Override
    public TraversalStepResult step(ChainSearchContext context, ParallelTickControl control, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        return ChainSearchAlgorithm.step(context, control, budgetState, matcher, consumer);
    }
}
