package club.heiqi.qz_miner.chain.planner;

import java.util.Arrays;
import java.util.List;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** BFS 分片驱动：预算观察、当前层终裁、邻接生成与层旋转各自持有恢复状态。 */
public final class ChainSearchAlgorithm {
    private ChainSearchAlgorithm() {}

    public static TraversalStepResult step(ChainSearchContext context, ParallelTickControl control,
            BudgetState state, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (context == null || control == null || state == null || matcher == null || consumer == null) {
            return TraversalStepResult.COMPLETED;
        }
        while (true) {
            TraversalStepResult stopped = TraversalBudget.observeWithTargetLimit(context, control);
            if (stopped != TraversalStepResult.CONTINUE) {
                if (stopped == TraversalStepResult.COMPLETED) {
                    state.reset();
                }
                return stopped;
            }
            TraversalStepResult result = advance(context, control, state, matcher, consumer);
            if (result != TraversalStepResult.CONTINUE) {
                return result;
            }
        }
    }

    private static TraversalStepResult advance(ChainSearchContext context, ParallelTickControl control,
            BudgetState state, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        switch (state.phase) {
            case ROTATE_FRONTIER:
                return state.rotateFrontier(context, control);
            case GENERATE_NEIGHBORS:
                return state.generateNeighbors(context, control);
            default:
                return advanceCurrentFrontier(context, control, state, matcher, consumer);
        }
    }

    private static TraversalStepResult advanceCurrentFrontier(ChainSearchContext context, ParallelTickControl control,
            BudgetState state, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (state.target.isEmpty() && context.getCurrentFrontier().isEmpty()) {
            return state.finishCurrentFrontier(context, control);
        }
        TraversalStepResult result = state.target.advance(context, control, matcher, consumer);
        if (result != TraversalStepResult.CONTINUE || !state.target.isAccepted()) {
            return result;
        }
        if (context.getConfirmedCount() >= context.getMaxTargets()) {
            state.reset();
            return TraversalStepResult.COMPLETED;
        }
        state.beginSeedNeighborGeneration(state.target.get());
        return TraversalStepResult.CONTINUE;
    }

    /** 同一轮搜索唯一的可恢复状态；拓扑游标不再与目标终裁阶段混放。 */
    public static final class BudgetState {
        private final TraversalTarget target = new TraversalTarget(true);
        private final TraversalNeighbors neighbors;
        private BudgetPhase phase = BudgetPhase.PROCESS_CURRENT_FRONTIER;

        public BudgetState() {
            this(new FaceNeighbors());
        }

        BudgetState(TraversalNeighbors neighbors) {
            this.neighbors = neighbors;
        }

        /** 破坏后 origin 只作为冻结拓扑种子，不在 seed 阶段重新读取世界。 */
        void beginSeedNeighborGeneration(ChainTarget origin) {
            target.clear();
            neighbors.begin(origin);
            phase = BudgetPhase.GENERATE_NEIGHBORS;
        }

        private TraversalStepResult generateNeighbors(ChainSearchContext context, ParallelTickControl control) {
            TraversalStepResult result = neighbors.step(context, control);
            if (result == TraversalStepResult.CONTINUE) {
                phase = BudgetPhase.PROCESS_CURRENT_FRONTIER;
            }
            return result;
        }

        private TraversalStepResult rotateFrontier(ChainSearchContext context, ParallelTickControl control) {
            TraversalStepResult result = TraversalBudget.rotateFrontier(context, control);
            if (result == TraversalStepResult.CONTINUE) {
                phase = BudgetPhase.PROCESS_CURRENT_FRONTIER;
            }
            return result;
        }

        private TraversalStepResult finishCurrentFrontier(ChainSearchContext context, ParallelTickControl control) {
            if (control.shouldYield()) {
                return PlanningCandidateGate.yieldOrTerminate(control);
            }
            if (!context.getNextFrontier().isEmpty()) {
                phase = BudgetPhase.ROTATE_FRONTIER;
                return TraversalStepResult.CONTINUE;
            }
            reset();
            return TraversalStepResult.COMPLETED;
        }

        private void reset() {
            target.clear();
            neighbors.clear();
            phase = BudgetPhase.PROCESS_CURRENT_FRONTIER;
        }
    }

    private enum BudgetPhase {
        PROCESS_CURRENT_FRONTIER, GENERATE_NEIGHBORS, ROTATE_FRONTIER
    }

    /** 六面邻接顺序沿用原 BFS；只生成坐标，不参与接纳终裁。 */
    private static final class FaceNeighbors extends TraversalNeighbors {
        private static final List<ChainTarget> OFFSETS = Arrays.asList(
                new ChainTarget(1, 0, 0), new ChainTarget(-1, 0, 0),
                new ChainTarget(0, 1, 0), new ChainTarget(0, -1, 0),
                new ChainTarget(0, 0, 1), new ChainTarget(0, 0, -1));
        private int index;

        @Override protected void resetCursor() { index = 0; }
        @Override protected boolean hasNext() { return center != null && index < OFFSETS.size(); }
        @Override protected void advanceCursor() { index++; }

        @Override
        protected ChainTarget currentNeighbor() {
            ChainTarget offset = OFFSETS.get(index);
            return new ChainTarget(center.getX() + offset.getX(), center.getY() + offset.getY(),
                    center.getZ() + offset.getZ());
        }

        @Override
        protected boolean isExcluded(ChainSearchContext context, ChainTarget next) {
            return super.isExcluded(context, next) || context.getConfirmedCount() >= context.getMaxTargets();
        }
    }
}
