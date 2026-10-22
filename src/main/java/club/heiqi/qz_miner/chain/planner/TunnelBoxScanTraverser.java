package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 隧道仅定义前向截面与 A/B 游标，目标终裁使用空间扫描公共驱动。 */
public class TunnelBoxScanTraverser extends SpatialScanTraverser {
    private final ChainTarget forward;
    private final ChainTarget lateralA;
    private final ChainTarget lateralB;
    private final TraversalCandidate candidate = new TraversalCandidate();
    private int depth;
    private int a;
    private int b;
    private boolean active;

    public TunnelBoxScanTraverser(int face) {
        int normalized = normalizeFace(face);
        forward = resolveForward(normalized);
        lateralA = resolveLateralA(normalized);
        lateralB = resolveLateralB(normalized);
    }

    @Override
    public void seed(ChainSearchContext context) {
        active = false;
        candidate.clear();
        resetTarget();
        context.getVisited().add(context.getOrigin());
        context.setScanDepth(0);
    }

    @Override
    protected TraversalStepResult enqueueNextBatch(ChainSearchContext context, ParallelTickControl control) {
        if (!active) {
            depth = context.getScanDepth();
            if (!hasNextSliceDepth(depth, context.getMaxRadius())) {
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return PlanningCandidateGate.yieldOrTerminate(control);
            }
            a = -1;
            b = -1;
            active = true;
        }
        return enqueueNextSlice(context, control);
    }

    private TraversalStepResult enqueueNextSlice(ChainSearchContext context, ParallelTickControl control) {
        while (a <= 1) {
            TraversalStepResult result = candidate.isPending()
                    ? commitPending(context, control) : discover(context, control);
            if (result != TraversalStepResult.CONTINUE) {
                return result;
            }
        }
        context.setScanDepth(depth + 1);
        active = false;
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult commitPending(ChainSearchContext context, ParallelTickControl control) {
        TraversalStepResult result = candidate.filter(context, control, context.getCurrentFrontier());
        if (result == TraversalStepResult.CONTINUE) {
            advanceCursor();
        }
        return result;
    }

    private TraversalStepResult discover(ChainSearchContext context, ParallelTickControl control) {
        ChainTarget next = createCandidate(context.getOrigin());
        if (context.getVisited().contains(next)) {
            if (control.shouldYield()) {
                return PlanningCandidateGate.yieldOrTerminate(control);
            }
            advanceCursor();
            context.recordDurableProgress();
            return TraversalStepResult.CONTINUE;
        }
        TraversalStepResult result = candidate.read(context, control, next);
        if (result == TraversalStepResult.CONTINUE && !candidate.isPending()) {
            advanceCursor();
        }
        return result;
    }

    private ChainTarget createCandidate(ChainTarget origin) {
        return new ChainTarget(
                origin.getX() + forward.getX() * depth + lateralA.getX() * a + lateralB.getX() * b,
                origin.getY() + forward.getY() * depth + lateralA.getY() * a + lateralB.getY() * b,
                origin.getZ() + forward.getZ() * depth + lateralA.getZ() * a + lateralB.getZ() * b);
    }

    private void advanceCursor() {
        b++;
        if (b > 1) {
            b = -1;
            a++;
        }
    }

    @Override
    protected boolean hasMoreWork(ChainSearchContext context) {
        return active || hasNextSliceDepth(context.getScanDepth(), context.getMaxRadius());
    }

    private boolean hasNextSliceDepth(int nextDepth, int maxRadius) {
        return nextDepth == 0 || nextDepth < maxRadius;
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
