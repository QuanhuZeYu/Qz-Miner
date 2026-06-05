package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 3x3x半径的指向性隧道遍历器。
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
    private ChainTarget currentTarget;

    public TunnelBoxScanTraverser(int face) {
        this.face = normalizeFace(face);
        this.forward = resolveForward(this.face);
        this.lateralA = resolveLateralA(this.face);
        this.lateralB = resolveLateralB(this.face);
    }

    @Override
    public void seed(ChainSearchContext context) {
        resetSliceEnqueueState();
        currentTarget = null;
        context.getVisited().add(context.getOrigin());
        context.setScanDepth(0);
    }

    @Override
    public boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        int processed = 0;

        if (context.getConfirmedCount() >= context.getMaxTargets()) {
            return false;
        }

        while (processed < maxNodes) {
            if (context.getCurrentFrontier().isEmpty()) {
                if (!enqueueNextSlice(context)) {
                    return false;
                }
            }

            ChainTarget current = context.getCurrentFrontier().poll();
            if (current == null) {
                continue;
            }

            if (!matcher.matches(current)) {
                processed++;
                continue;
            }

            consumer.accept(current);
            context.incrementConfirmedCount();
            processed++;

            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                break;
            }
        }

        return context.getConfirmedCount() < context.getMaxTargets()
            && (!context.getCurrentFrontier().isEmpty() || hasMoreSliceWork(context));
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
                currentTarget = null;
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            if (currentTarget == null && context.getCurrentFrontier().isEmpty()) {
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }

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
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }
                currentTarget = context.getCurrentFrontier().poll();
                if (currentTarget == null) {
                    continue;
                }
            }

            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            if (!matcher.matches(currentTarget)) {
                currentTarget = null;
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
            currentTarget = null;
        }
    }

    private boolean enqueueNextSlice(ChainSearchContext context) {
        int nextDepth = context.getScanDepth();
        if (!hasNextSliceDepth(nextDepth, context.getMaxRadius())) {
            return false;
        }

        enqueueSlice(context, nextDepth);
        context.setScanDepth(nextDepth + 1);
        return true;
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
            beginSliceEnqueue(nextDepth);
        }

        ChainTarget origin = context.getOrigin();
        for (int a = enqueueA; a <= 1; a++) {
            int bStart = a == enqueueA ? enqueueB : -1;
            for (int b = bStart; b <= 1; b++) {
                if (!control.tryConsumeWork(1)) {
                    saveSliceCursor(a, b);
                    return yieldOrTerminate(control);
                }

                ChainTarget candidate = createCandidate(origin, enqueueDepth, a, b);
                if (!context.getVisited().add(candidate)) {
                    continue;
                }

                if (!context.canTraverse(candidate)) {
                    continue;
                }

                context.getCurrentFrontier().add(candidate);
            }
        }

        context.setScanDepth(enqueueDepth + 1);
        resetSliceEnqueueState();
        return TraversalStepResult.CONTINUE;
    }

    private void enqueueSlice(ChainSearchContext context, int depth) {
        ChainTarget origin = context.getOrigin();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                ChainTarget candidate = createCandidate(origin, depth, a, b);

                if (!context.getVisited().add(candidate)) {
                    continue;
                }

                if (!context.canTraverse(candidate)) {
                    continue;
                }

                context.getCurrentFrontier().add(candidate);
            }
        }
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
    }

    private boolean hasMoreSliceWork(ChainSearchContext context) {
        return sliceEnqueueInProgress || hasNextSliceDepth(context.getScanDepth(), context.getMaxRadius());
    }

    private boolean hasNextSliceDepth(int depth, int maxRadius) {
        return depth == 0 || depth < maxRadius;
    }

    private TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return control.isCancelRequested() ? TraversalStepResult.TERMINATED : TraversalStepResult.YIELDED;
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
