package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 邻接游标只定义拓扑；读取、filter、visited 与下一层入队在此统一提交。 */
abstract class TraversalNeighbors {
    private final TraversalCandidate candidate = new TraversalCandidate();
    protected ChainTarget center;

    final void begin(ChainTarget source) {
        center = source;
        candidate.clear();
        resetCursor();
    }

    final void clear() {
        center = null;
        candidate.clear();
        resetCursor();
    }

    final TraversalStepResult step(ChainSearchContext context, ParallelTickControl control) {
        TraversalStepResult prepared = prepare(context, control);
        if (prepared != TraversalStepResult.CONTINUE) {
            return prepared;
        }
        while (hasNext()) {
            TraversalStepResult stopped = TraversalBudget.observe(control);
            if (stopped != TraversalStepResult.CONTINUE) {
                return stopped;
            }
            TraversalStepResult result = candidate.isPending()
                    ? commitPending(context, control) : discover(context, control);
            if (result != TraversalStepResult.CONTINUE) {
                return result;
            }
        }
        clear();
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult commitPending(ChainSearchContext context, ParallelTickControl control) {
        TraversalStepResult result = candidate.filter(context, control, context.getNextFrontier());
        if (result == TraversalStepResult.CONTINUE) {
            advanceCursor();
        }
        return result;
    }

    private TraversalStepResult discover(ChainSearchContext context, ParallelTickControl control) {
        ChainTarget next = currentNeighbor();
        if (next == null || isExcluded(context, next)) {
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

    protected TraversalStepResult prepare(ChainSearchContext context, ParallelTickControl control) {
        return TraversalStepResult.CONTINUE;
    }

    protected boolean isExcluded(ChainSearchContext context, ChainTarget next) {
        return context.getVisited().contains(next) || distance(next, context.getOrigin()) > context.getMaxRadius();
    }

    protected abstract void resetCursor();
    protected abstract boolean hasNext();
    protected abstract ChainTarget currentNeighbor();
    protected abstract void advanceCursor();

    static int distance(ChainTarget a, ChainTarget b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, Math.max(dy, dz));
    }
}
