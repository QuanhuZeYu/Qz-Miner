package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 伐木复用 BFS 恢复流程，仅由立方邻接游标定义原木壳层范围。 */
public class LoggingFloodFillTraverser implements BudgetedChainTraverser {
    private final ChainSearchAlgorithm.BudgetState budgetState;

    public LoggingFloodFillTraverser(int shellLayers) {
        budgetState = new ChainSearchAlgorithm.BudgetState(new LoggingNeighbors(Math.max(1, shellLayers)));
    }

    @Override
    public void seed(ChainSearchContext context) {
        ChainTarget origin = context.getOrigin();
        context.getVisited().add(origin);
        budgetState.beginSeedNeighborGeneration(origin);
    }

    @Override
    public TraversalStepResult step(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        return ChainSearchAlgorithm.step(context, control, budgetState, matcher, consumer);
    }

    /** X、Y、Z 依次递增；伐木沿用局部壳层连通，不套用普通 CHAIN 的 origin 半径门。 */
    private static final class LoggingNeighbors extends TraversalNeighbors {
        private final int layers;
        private int x;
        private int y;
        private int z;

        private LoggingNeighbors(int layers) {
            this.layers = layers;
        }

        @Override protected boolean hasNext() { return center != null && x <= layers; }
        @Override protected boolean isExcluded(ChainSearchContext context, ChainTarget next) {
            return context.getVisited().contains(next);
        }

        @Override
        protected void resetCursor() {
            x = -layers;
            y = -layers;
            z = -layers;
        }

        @Override
        protected ChainTarget currentNeighbor() {
            if (x == 0 && y == 0 && z == 0) {
                return null;
            }
            return new ChainTarget(center.getX() + x, center.getY() + y, center.getZ() + z);
        }

        @Override
        protected void advanceCursor() {
            z++;
            if (z <= layers) {
                return;
            }
            z = -layers;
            y++;
            if (y <= layers) {
                return;
            }
            y = -layers;
            x++;
        }
    }
}
