package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.chain.selection.CuboidBounds;
import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 以单坐标游标预算化扫描冻结 inclusive 选区，不预展开体积。 */
public final class CuboidScanTraverser implements BudgetedChainTraverser {

    private final CuboidBounds bounds;
    private int x;
    private int y;
    private int z;
    private ChainTarget currentTarget;
    private Phase phase;
    private boolean finished;

    public CuboidScanTraverser(CuboidBounds bounds) {
        if (bounds == null) throw new IllegalArgumentException("bounds must not be null");
        this.bounds = bounds;
    }

    @Override
    public void seed(ChainSearchContext context) {
        x = bounds.getMinX();
        y = bounds.getMinY();
        z = bounds.getMinZ();
        currentTarget = null;
        phase = Phase.COMMIT_CANDIDATE;
        finished = false;
        if (context != null) context.setScanDepth(0);
    }

    @Override
    public TraversalStepResult step(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (context == null || control == null || matcher == null || consumer == null) {
            return TraversalStepResult.COMPLETED;
        }
        while (!finished) {
            if (control.isCancelRequested()) return TraversalStepResult.TERMINATED;
            if (context.getConfirmedCount() >= context.getMaxTargets()) return TraversalStepResult.COMPLETED;
            if (control.shouldYield()) return TraversalStepResult.YIELDED;

            if (currentTarget == null) currentTarget = new ChainTarget(x, y, z);
            if (phase == Phase.COMMIT_CANDIDATE) {
                PlanningCandidateWorkBudget.CommitResult result =
                        context.tryCommitPlanningCandidate(control, currentTarget);
                if (result == PlanningCandidateWorkBudget.CommitResult.YIELDED) return TraversalStepResult.YIELDED;
                if (result == PlanningCandidateWorkBudget.CommitResult.TERMINATED) return TraversalStepResult.TERMINATED;
                if (result == PlanningCandidateWorkBudget.CommitResult.AIR_COMMITTED) {
                    advance();
                    continue;
                }
                if (!context.canTraverse(currentTarget)) {
                    advance();
                    continue;
                }
                phase = Phase.CHECK_MATCHER;
            }

            if (phase == Phase.CHECK_MATCHER) {
                if (!control.tryConsumeWork(1)) return yieldOrTerminate(control);
                if (!matcher.matches(currentTarget)) {
                    advance();
                    continue;
                }
                phase = Phase.SUBMIT_TARGET;
            }

            if (!control.tryConsumeWork(1)) return yieldOrTerminate(control);
            if (control.isCancelRequested()) return TraversalStepResult.TERMINATED;
            consumer.accept(currentTarget);
            context.incrementConfirmedCount();
            advance();
        }
        return TraversalStepResult.COMPLETED;
    }

    private void advance() {
        currentTarget = null;
        phase = Phase.COMMIT_CANDIDATE;
        if (x != bounds.getMaxX()) {
            x++;
        } else if (y != bounds.getMaxY()) {
            x = bounds.getMinX();
            y++;
        } else if (z != bounds.getMaxZ()) {
            x = bounds.getMinX();
            y = bounds.getMinY();
            z++;
        } else {
            finished = true;
        }
    }

    private static TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return control.isCancelRequested() ? TraversalStepResult.TERMINATED : TraversalStepResult.YIELDED;
    }

    private enum Phase { COMMIT_CANDIDATE, CHECK_MATCHER, SUBMIT_TARGET }
}
