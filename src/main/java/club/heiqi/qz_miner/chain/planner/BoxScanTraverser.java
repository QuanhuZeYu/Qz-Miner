package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 盒扫预算化遍历器。
 */
public class BoxScanTraverser implements BudgetedChainTraverser {

    private int enqueueDepth;
    private int enqueueX;
    private int enqueueY;
    private int enqueueZ;
    private boolean shellEnqueueInProgress;
    private ChainTarget pendingEnqueueCandidate;
    private ChainTarget currentTarget;
    private CurrentTargetPhase currentTargetPhase = CurrentTargetPhase.CHECK_MATCHER;

    /**
     * 初始化盒扫状态，但不一次性装填整盒候选点。
     *
     * @param context 搜索上下文
     */
    @Override
    public void seed(ChainSearchContext context) {
        resetEnqueueState();
        clearCurrentTarget();
        context.getVisited().add(context.getOrigin());
        context.setScanDepth(0);
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
                clearCurrentTarget();
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            if (currentTarget == null && context.getCurrentFrontier().isEmpty()) {
                TraversalStepResult enqueueResult = enqueueNextShell(context, control);
                if (enqueueResult != TraversalStepResult.CONTINUE) {
                    return enqueueResult;
                }
                if (context.getCurrentFrontier().isEmpty()) {
                    if (hasMoreShellWork(context)) {
                        continue;
                    }
                    return TraversalStepResult.COMPLETED;
                }
            }

            if (currentTarget == null) {
                ChainTarget queuedTarget = context.getCurrentFrontier().peek();
                PlanningCandidateGate.CommitResult candidateResult =
                        context.tryCommitPlanningCandidate(control, queuedTarget);
                TraversalStepResult committed = PlanningCandidateGate.commitStep(candidateResult, control);
                if (committed != null) {
                    return committed;
                }
                currentTarget = context.getCurrentFrontier().poll();
                if (currentTarget == null) {
                    continue;
                }
                if (candidateResult == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
                    clearCurrentTarget();
                    continue;
                }
                currentTargetPhase = CurrentTargetPhase.CHECK_MATCHER;
            }

            if (currentTargetPhase == CurrentTargetPhase.CHECK_MATCHER) {
                if (control.shouldYield()) {
                    return yieldOrTerminate(control);
                }
                if (!matcher.matches(currentTarget)) {
                    clearCurrentTarget();
                    continue;
                }
                currentTargetPhase = CurrentTargetPhase.SUBMIT_TARGET;
                continue;
            }

            if (control.shouldYield()) {
                return yieldOrTerminate(control);
            }
            if (control.isCancelRequested()) {
                return TraversalStepResult.TERMINATED;
            }
            consumer.accept(currentTarget);
            context.incrementConfirmedCount();
            clearCurrentTarget();
        }
    }

    /**
     * 按预算推进下一层外壳扫描。
     *
     * @param context 搜索上下文
     * @param control 当前并行 Tick 控制对象
     * @return 当前预算化装填结果
     */
    private TraversalStepResult enqueueNextShell(ChainSearchContext context, ParallelTickControl control) {
        if (context == null || context.getOrigin() == null) {
            return TraversalStepResult.COMPLETED;
        }

        if (!shellEnqueueInProgress) {
            int nextDepth = context.getScanDepth() + 1;
            if (nextDepth > context.getMaxRadius()) {
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return yieldOrTerminate(control);
            }
            beginShellEnqueue(context, nextDepth);
        }

        ChainTarget origin = context.getOrigin();
        int minX = origin.getX() - enqueueDepth;
        int maxX = origin.getX() + enqueueDepth;
        int minY = origin.getY() - enqueueDepth;
        int maxY = origin.getY() + enqueueDepth;
        int minZ = origin.getZ() - enqueueDepth;
        int maxZ = origin.getZ() + enqueueDepth;

        TraversalStepResult pendingResult = commitPendingEnqueueCandidate(context, control);
        if (pendingResult != TraversalStepResult.CONTINUE) {
            return pendingResult;
        }

        for (int x = enqueueX; x <= maxX; x++) {
            int yStart = x == enqueueX ? enqueueY : minY;
            for (int y = yStart; y <= maxY; y++) {
                int zStart = x == enqueueX && y == yStart ? enqueueZ : minZ;
                for (int z = zStart; z <= maxZ; z++) {
                    if (!isOnShell(origin, enqueueDepth, x, y, z)) {
                        if (control.shouldYield()) {
                            saveCursor(x, y, z);
                            return yieldOrTerminate(control);
                        }
                        context.recordDurableProgress();
                        continue;
                    }

                    ChainTarget candidate = new ChainTarget(x, y, z);
                    if (context.getVisited().contains(candidate)) {
                        if (control.shouldYield()) {
                            saveCursor(x, y, z);
                            return yieldOrTerminate(control);
                        }
                        context.recordDurableProgress();
                        continue;
                    }

                    PlanningCandidateGate.CommitResult candidateResult =
                            context.tryCommitPlanningCandidate(control, candidate);
                    TraversalStepResult committed = PlanningCandidateGate.commitStep(candidateResult, control);
                    if (committed != null) {
                        // 让出/终止才保存游标（已提交候选由调用方继续处理）；显式保存避免 lambda 捕获循环变量。
                        saveCursor(x, y, z);
                        return committed;
                    }

                    if (candidateResult == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
                        context.getVisited().add(candidate);
                        continue;
                    }
                    pendingEnqueueCandidate = candidate;
                    saveCursor(x, y, z);
                    pendingResult = commitPendingEnqueueCandidate(context, control);
                    if (pendingResult != TraversalStepResult.CONTINUE) {
                        return pendingResult;
                    }
                }
            }
        }

        context.setScanDepth(enqueueDepth);
        resetEnqueueState();
        return TraversalStepResult.CONTINUE;
    }

    private void beginShellEnqueue(ChainSearchContext context, int nextDepth) {
        ChainTarget origin = context.getOrigin();
        enqueueDepth = nextDepth;
        enqueueX = origin.getX() - nextDepth;
        enqueueY = origin.getY() - nextDepth;
        enqueueZ = origin.getZ() - nextDepth;
        shellEnqueueInProgress = true;
    }

    private void saveCursor(int x, int y, int z) {
        enqueueX = x;
        enqueueY = y;
        enqueueZ = z;
    }

    private void resetEnqueueState() {
        enqueueDepth = 0;
        enqueueX = 0;
        enqueueY = 0;
        enqueueZ = 0;
        shellEnqueueInProgress = false;
        pendingEnqueueCandidate = null;
    }

    private TraversalStepResult commitPendingEnqueueCandidate(
            ChainSearchContext context, ParallelTickControl control) {
        if (pendingEnqueueCandidate == null) {
            return TraversalStepResult.CONTINUE;
        }
        PlanningCandidateGate.FilterResult filterResult =
                context.tryCommitPlanningCandidateFilter(control, pendingEnqueueCandidate);
        TraversalStepResult filtered = PlanningCandidateGate.filterStep(filterResult, control);
        if (filtered != null) {
            return filtered;
        }
        ChainTarget candidate = pendingEnqueueCandidate;
        pendingEnqueueCandidate = null;
        if (context.getVisited().add(candidate)
                && filterResult == PlanningCandidateGate.FilterResult.ACCEPTED) {
            context.getCurrentFrontier().add(candidate);
        }
        return TraversalStepResult.CONTINUE;
    }

    private boolean hasMoreShellWork(ChainSearchContext context) {
        return shellEnqueueInProgress || context.getScanDepth() < context.getMaxRadius();
    }

    private TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return PlanningCandidateGate.yieldOrTerminate(control);
    }

    private void clearCurrentTarget() {
        currentTarget = null;
        currentTargetPhase = CurrentTargetPhase.CHECK_MATCHER;
    }

    private enum CurrentTargetPhase {
        CHECK_MATCHER,
        SUBMIT_TARGET
    }

    /**
     * 判断当前坐标是否位于指定半径的外壳表面。
     *
     * @param origin 中心点
     * @param depth 当前壳层半径
     * @param x 候选 X 坐标
     * @param y 候选 Y 坐标
     * @param z 候选 Z 坐标
     * @return 是否位于当前壳层
     */
    private boolean isOnShell(ChainTarget origin, int depth, int x, int y, int z) {
        int dx = Math.abs(x - origin.getX());
        int dy = Math.abs(y - origin.getY());
        int dz = Math.abs(z - origin.getZ());
        return Math.max(dx, Math.max(dy, dz)) == depth;
    }
}
