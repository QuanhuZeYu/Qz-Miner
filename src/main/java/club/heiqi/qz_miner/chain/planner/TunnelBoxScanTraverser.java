package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 3x3x半径的指向性隧道预算化遍历器。
 */
public class TunnelBoxScanTraverser implements BudgetedChainTraverser {

    private final int face;
    private final ChainTarget forward;
    private final ChainTarget lateralA;
    private final ChainTarget lateralB;
    private int enqueueDepth;
    private int enqueueA;
    private int enqueueB;
    private boolean sliceEnqueueInProgress;
    private ChainTarget pendingEnqueueCandidate;
    private ChainTarget currentTarget;
    private CurrentTargetPhase currentTargetPhase = CurrentTargetPhase.CHECK_MATCHER;

    public TunnelBoxScanTraverser(int face) {
        this.face = normalizeFace(face);
        this.forward = resolveForward(this.face);
        this.lateralA = resolveLateralA(this.face);
        this.lateralB = resolveLateralB(this.face);
    }

    @Override
    public void seed(ChainSearchContext context) {
        resetSliceEnqueueState();
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
                TraversalStepResult enqueueResult = enqueueNextSlice(context, control);
                if (enqueueResult != TraversalStepResult.CONTINUE) {
                    return enqueueResult;
                }
                if (context.getCurrentFrontier().isEmpty()) {
                    if (hasMoreSliceWork(context)) {
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
     * 按预算推进下一片隧道截面扫描。
     *
     * @param context 搜索上下文
     * @param control 当前并行 Tick 控制对象
     * @return 当前预算化装填结果
     */
    private TraversalStepResult enqueueNextSlice(ChainSearchContext context, ParallelTickControl control) {
        if (!sliceEnqueueInProgress) {
            int nextDepth = context.getScanDepth();
            if (!hasNextSliceDepth(nextDepth, context.getMaxRadius())) {
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return yieldOrTerminate(control);
            }
            beginSliceEnqueue(nextDepth);
        }

        ChainTarget origin = context.getOrigin();
        TraversalStepResult pendingResult = commitPendingEnqueueCandidate(context, control);
        if (pendingResult != TraversalStepResult.CONTINUE) {
            return pendingResult;
        }
        for (int a = enqueueA; a <= 1; a++) {
            int bStart = a == enqueueA ? enqueueB : -1;
            for (int b = bStart; b <= 1; b++) {
                ChainTarget candidate = createCandidate(origin, enqueueDepth, a, b);
                if (context.getVisited().contains(candidate)) {
                    if (control.shouldYield()) {
                        saveSliceCursor(a, b);
                        return yieldOrTerminate(control);
                    }
                    context.recordDurableProgress();
                    continue;
                }

                PlanningCandidateGate.CommitResult candidateResult =
                        context.tryCommitPlanningCandidate(control, candidate);
                TraversalStepResult committed = PlanningCandidateGate.commitStep(candidateResult, control);
                if (committed != null) {
                    // 让出/终止才保存游标；显式保存避免 lambda 捕获循环变量。
                    saveSliceCursor(a, b);
                    return committed;
                }

                if (candidateResult == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
                    context.getVisited().add(candidate);
                    continue;
                }
                pendingEnqueueCandidate = candidate;
                saveSliceCursor(a, b);
                pendingResult = commitPendingEnqueueCandidate(context, control);
                if (pendingResult != TraversalStepResult.CONTINUE) {
                    return pendingResult;
                }
            }
        }

        context.setScanDepth(enqueueDepth + 1);
        resetSliceEnqueueState();
        return TraversalStepResult.CONTINUE;
    }

    private ChainTarget createCandidate(ChainTarget origin, int depth, int a, int b) {
        return new ChainTarget(
            origin.getX() + forward.getX() * depth + lateralA.getX() * a + lateralB.getX() * b,
            origin.getY() + forward.getY() * depth + lateralA.getY() * a + lateralB.getY() * b,
            origin.getZ() + forward.getZ() * depth + lateralA.getZ() * a + lateralB.getZ() * b);
    }

    private void beginSliceEnqueue(int depth) {
        enqueueDepth = depth;
        enqueueA = -1;
        enqueueB = -1;
        sliceEnqueueInProgress = true;
    }

    private void saveSliceCursor(int a, int b) {
        enqueueA = a;
        enqueueB = b;
    }

    private void resetSliceEnqueueState() {
        enqueueDepth = 0;
        enqueueA = -1;
        enqueueB = -1;
        sliceEnqueueInProgress = false;
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

    private boolean hasMoreSliceWork(ChainSearchContext context) {
        return sliceEnqueueInProgress || hasNextSliceDepth(context.getScanDepth(), context.getMaxRadius());
    }

    private boolean hasNextSliceDepth(int depth, int maxRadius) {
        return depth == 0 || depth < maxRadius;
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

    private static int normalizeFace(int face) {
        return face < 0 || face > 5 ? 1 : face;
    }

    private static ChainTarget resolveForward(int face) {
        switch (face) {
            case 0:
                return new ChainTarget(0, -1, 0);
            case 1:
                return new ChainTarget(0, 1, 0);
            case 2:
                return new ChainTarget(0, 0, -1);
            case 3:
                return new ChainTarget(0, 0, 1);
            case 4:
                return new ChainTarget(-1, 0, 0);
            case 5:
            default:
                return new ChainTarget(1, 0, 0);
        }
    }

    private static ChainTarget resolveLateralA(int face) {
        switch (face) {
            case 0:
            case 1:
                return new ChainTarget(1, 0, 0);
            case 2:
            case 3:
                return new ChainTarget(1, 0, 0);
            case 4:
            case 5:
            default:
                return new ChainTarget(0, 1, 0);
        }
    }

    private static ChainTarget resolveLateralB(int face) {
        switch (face) {
            case 0:
            case 1:
                return new ChainTarget(0, 0, 1);
            case 2:
            case 3:
                return new ChainTarget(0, 1, 0);
            case 4:
            case 5:
            default:
                return new ChainTarget(0, 0, 1);
        }
    }
}
