package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 按 origin 半径逐层装填，终裁与恢复由空间遍历公共驱动负责。 */
public class BoxScanTraverser extends SpatialScanTraverser {
    private final ShellScan shell = new ShellScan();

    @Override
    public void seed(ChainSearchContext context) {
        shell.reset();
        resetTarget();
        context.getVisited().add(context.getOrigin());
        context.setScanDepth(0);
    }

    @Override
    protected TraversalStepResult enqueueNextBatch(ChainSearchContext context, ParallelTickControl control) {
        if (context.getOrigin() == null) {
            return TraversalStepResult.COMPLETED;
        }
        if (!shell.isActive()) {
            int nextDepth = context.getScanDepth() + 1;
            if (nextDepth > context.getMaxRadius()) {
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return PlanningCandidateGate.yieldOrTerminate(control);
            }
            shell.begin(context.getOrigin(), nextDepth, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        return shell.step(context, control);
    }

    @Override
    protected boolean hasMoreWork(ChainSearchContext context) {
        return shell.isActive() || context.getScanDepth() < context.getMaxRadius();
    }
}
