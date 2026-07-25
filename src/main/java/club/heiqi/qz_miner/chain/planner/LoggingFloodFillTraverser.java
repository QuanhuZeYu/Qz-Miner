package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 伐木壳层预算化遍历器。
 */
public class LoggingFloodFillTraverser implements BudgetedChainTraverser {

    private final int shellLayers;
    private TraversalPhase budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
    private ChainTarget currentTarget;
    private int offsetX;
    private int offsetY;
    private int offsetZ;

    public LoggingFloodFillTraverser(int shellLayers) {
        this.shellLayers = Math.max(1, shellLayers);
    }

    @Override
    public void seed(ChainSearchContext context) {
        resetBudgetState();
        ChainTarget origin = context.getOrigin();
        context.getVisited().add(origin);
        beginNeighborGeneration(origin);
    }

    @Override
    public TraversalStepResult step(ChainSearchContext context, ParallelTickControl control, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (context == null || control == null || matcher == null || consumer == null) {
            return TraversalStepResult.COMPLETED;
        }

        while (true) {
            if (control.isCancelRequested()) {
                return TraversalStepResult.TERMINATED;
            }
            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                resetBudgetState();
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            if (budgetPhase == TraversalPhase.ROTATE_FRONTIER) {
                TraversalStepResult rotateResult = rotateFrontier(context, control);
                if (rotateResult != TraversalStepResult.CONTINUE) {
                    return rotateResult;
                }
                continue;
            }

            if (budgetPhase == TraversalPhase.GENERATE_NEIGHBORS) {
                TraversalStepResult neighborResult = generateNeighbors(context, control);
                if (neighborResult != TraversalStepResult.CONTINUE) {
                    return neighborResult;
                }
                continue;
            }

            if (budgetPhase == TraversalPhase.CHECK_CURRENT_MATCHER) {
                if (currentTarget == null) {
                    clearCurrentTarget();
                    continue;
                }
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }
                if (!matcher.matches(currentTarget)) {
                    // CHAIN_LOGGING 的 matcher 同时承担冻结采掘能力门；拒绝节点既不入队，
                    // 也不得生成邻居，避免无可用能力的原木桥接后续拓扑。
                    clearCurrentTarget();
                    continue;
                }
                budgetPhase = TraversalPhase.SUBMIT_CURRENT_TARGET;
                continue;
            }

            if (budgetPhase == TraversalPhase.SUBMIT_CURRENT_TARGET) {
                if (currentTarget == null) {
                    clearCurrentTarget();
                    continue;
                }
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }
                if (control.isCancelRequested()) {
                    return TraversalStepResult.TERMINATED;
                }
                consumer.accept(currentTarget);
                context.incrementConfirmedCount();

                if (context.getConfirmedCount() >= context.getMaxTargets()) {
                    clearCurrentTarget();
                    return TraversalStepResult.COMPLETED;
                }

                beginNeighborGeneration(currentTarget);
                continue;
            }

            if (currentTarget == null && context.getCurrentFrontier().isEmpty()) {
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }
                if (!context.getNextFrontier().isEmpty()) {
                    budgetPhase = TraversalPhase.ROTATE_FRONTIER;
                    continue;
                }
                resetBudgetState();
                return TraversalStepResult.COMPLETED;
            }

            if (currentTarget == null) {
                ChainTarget queuedTarget = context.getCurrentFrontier().peek();
                PlanningCandidateWorkBudget.CommitResult candidateResult =
                        context.tryCommitPlanningCandidate(control, queuedTarget);
                if (candidateResult == PlanningCandidateWorkBudget.CommitResult.YIELDED) {
                    return TraversalStepResult.YIELDED;
                }
                if (candidateResult == PlanningCandidateWorkBudget.CommitResult.TERMINATED) {
                    return TraversalStepResult.TERMINATED;
                }
                currentTarget = context.getCurrentFrontier().poll();
                if (currentTarget == null) {
                    continue;
                }
                if (candidateResult == PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED) {
                    clearCurrentTarget();
                    continue;
                }
                if (!context.canTraverse(currentTarget)) {
                    clearCurrentTarget();
                    continue;
                }
            }
            budgetPhase = TraversalPhase.CHECK_CURRENT_MATCHER;
        }
    }

    private TraversalStepResult rotateFrontier(ChainSearchContext context, ParallelTickControl control) {
        while (!context.getNextFrontier().isEmpty()) {
            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            ChainTarget target = context.getNextFrontier().poll();
            if (target != null) {
                context.getCurrentFrontier().add(target);
            }
        }
        budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult generateNeighbors(ChainSearchContext context, ParallelTickControl control) {
        if (currentTarget == null) {
            clearCurrentTarget();
            return TraversalStepResult.CONTINUE;
        }

        while (hasMoreOffsets()) {
            if (control.isCancelRequested()) {
                return TraversalStepResult.TERMINATED;
            }
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            int dx = offsetX;
            int dy = offsetY;
            int dz = offsetZ;

            if (dx == 0 && dy == 0 && dz == 0) {
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }
                advanceOffsetCursor();
                continue;
            }

            ChainTarget next = new ChainTarget(
                currentTarget.getX() + dx,
                currentTarget.getY() + dy,
                currentTarget.getZ() + dz);
            if (context.getVisited().contains(next)) {
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }
                advanceOffsetCursor();
                continue;
            }

            PlanningCandidateWorkBudget.CommitResult candidateResult =
                    context.tryCommitPlanningCandidate(control, next);
            if (candidateResult == PlanningCandidateWorkBudget.CommitResult.YIELDED) {
                return TraversalStepResult.YIELDED;
            }
            if (candidateResult == PlanningCandidateWorkBudget.CommitResult.TERMINATED) {
                return TraversalStepResult.TERMINATED;
            }

            advanceOffsetCursor();
            if (!context.getVisited().add(next)
                    || candidateResult == PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED) {
                continue;
            }
            if (context.canTraverse(next)) {
                context.getNextFrontier().add(next);
            }
        }

        clearCurrentTarget();
        return TraversalStepResult.CONTINUE;
    }

    private void beginNeighborGeneration(ChainTarget center) {
        currentTarget = center;
        offsetX = -shellLayers;
        offsetY = -shellLayers;
        offsetZ = -shellLayers;
        budgetPhase = TraversalPhase.GENERATE_NEIGHBORS;
    }

    private boolean hasMoreOffsets() {
        return offsetX <= shellLayers;
    }

    private void advanceOffsetCursor() {
        offsetZ++;
        if (offsetZ <= shellLayers) {
            return;
        }
        offsetZ = -shellLayers;
        offsetY++;
        if (offsetY <= shellLayers) {
            return;
        }
        offsetY = -shellLayers;
        offsetX++;
    }

    private TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return control.isCancelRequested() ? TraversalStepResult.TERMINATED : TraversalStepResult.YIELDED;
    }

    private void clearCurrentTarget() {
        budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        currentTarget = null;
        offsetX = -shellLayers;
        offsetY = -shellLayers;
        offsetZ = -shellLayers;
    }

    private void resetBudgetState() {
        clearCurrentTarget();
        budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
    }

    private enum TraversalPhase {
        PROCESS_CURRENT_FRONTIER,
        CHECK_CURRENT_MATCHER,
        SUBMIT_CURRENT_TARGET,
        GENERATE_NEIGHBORS,
        ROTATE_FRONTIER
    }
}
