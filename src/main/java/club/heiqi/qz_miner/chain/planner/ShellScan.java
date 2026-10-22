package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 有界立方壳层游标，按 X/Y/Z 字典顺序推进；接纳事务未完成时不推进坐标。 */
final class ShellScan {
    private final TraversalCandidate candidate = new TraversalCandidate();
    private int depth;
    private int startY;
    private int startZ;
    private int endX;
    private int endY;
    private int endZ;
    private int x;
    private int y;
    private int z;
    private boolean active;

    boolean isActive() { return active; }

    void reset() {
        active = false;
        candidate.clear();
    }

    void begin(ChainTarget origin, int nextDepth, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        depth = nextDepth;
        x = Math.max(minX, origin.getX() - depth);
        startY = Math.max(minY, origin.getY() - depth);
        startZ = Math.max(minZ, origin.getZ() - depth);
        endX = Math.min(maxX, origin.getX() + depth);
        endY = Math.min(maxY, origin.getY() + depth);
        endZ = Math.min(maxZ, origin.getZ() + depth);
        y = startY;
        z = startZ;
        candidate.clear();
        active = true;
    }

    TraversalStepResult step(ChainSearchContext context, ParallelTickControl control) {
        while (x <= endX) {
            TraversalStepResult result = candidate.isPending()
                    ? commitPending(context, control) : discover(context, control);
            if (result != TraversalStepResult.CONTINUE) {
                return result;
            }
        }
        context.setScanDepth(depth);
        reset();
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult commitPending(ChainSearchContext context, ParallelTickControl control) {
        TraversalStepResult result = candidate.filter(context, control, context.getCurrentFrontier());
        if (result == TraversalStepResult.CONTINUE) {
            advance();
        }
        return result;
    }

    private TraversalStepResult discover(ChainSearchContext context, ParallelTickControl control) {
        if (!isOnShell(context.getOrigin())) {
            return skip(context, control);
        }
        ChainTarget next = new ChainTarget(x, y, z);
        if (context.getVisited().contains(next)) {
            return skip(context, control);
        }
        TraversalStepResult result = candidate.read(context, control, next);
        if (result == TraversalStepResult.CONTINUE && !candidate.isPending()) {
            advance();
        }
        return result;
    }

    private TraversalStepResult skip(ChainSearchContext context, ParallelTickControl control) {
        if (control.shouldYield()) {
            return PlanningCandidateGate.yieldOrTerminate(control);
        }
        advance();
        context.recordDurableProgress();
        return TraversalStepResult.CONTINUE;
    }

    private boolean isOnShell(ChainTarget origin) {
        return Math.max(Math.abs(x - origin.getX()),
                Math.max(Math.abs(y - origin.getY()), Math.abs(z - origin.getZ()))) == depth;
    }

    private void advance() {
        z++;
        if (z <= endZ) {
            return;
        }
        z = startZ;
        y++;
        if (y <= endY) {
            return;
        }
        y = startY;
        x++;
    }
}
