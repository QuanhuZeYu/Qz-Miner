package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/** 区段清理只定义 16x16x16 边界，壳层游标与目标终裁复用公共职责。 */
public class SectionClearTraverser extends SpatialScanTraverser {
    private static final int SECTION_SIZE = 16;
    private final ShellScan shell = new ShellScan();
    private int minX;
    private int maxX;
    private int minY;
    private int maxY;
    private int minZ;
    private int maxZ;
    private int maxDepth;

    @Override
    public void seed(ChainSearchContext context) {
        ChainTarget origin = context.getOrigin();
        minX = alignToSection(origin.getX());
        maxX = minX + SECTION_SIZE - 1;
        minY = alignToSection(origin.getY());
        maxY = minY + SECTION_SIZE - 1;
        minZ = alignToSection(origin.getZ());
        maxZ = minZ + SECTION_SIZE - 1;
        maxDepth = resolveMaxDepth(origin);
        shell.reset();
        resetTarget();
        context.getVisited().add(origin);
        context.setScanDepth(0);
    }

    @Override
    protected TraversalStepResult enqueueNextBatch(ChainSearchContext context, ParallelTickControl control) {
        if (!shell.isActive()) {
            int nextDepth = context.getScanDepth() + 1;
            if (nextDepth > maxDepth) {
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return PlanningCandidateGate.yieldOrTerminate(control);
            }
            shell.begin(context.getOrigin(), nextDepth, minX, maxX, minY, maxY, minZ, maxZ);
        }
        return shell.step(context, control);
    }

    @Override
    protected boolean hasMoreWork(ChainSearchContext context) {
        return shell.isActive() || context.getScanDepth() < maxDepth;
    }

    private static int alignToSection(int coordinate) {
        return (coordinate >> 4) << 4;
    }

    private int resolveMaxDepth(ChainTarget origin) {
        int x = Math.max(origin.getX() - minX, maxX - origin.getX());
        int y = Math.max(origin.getY() - minY, maxY - origin.getY());
        int z = Math.max(origin.getZ() - minZ, maxZ - origin.getZ());
        return Math.max(x, Math.max(y, z));
    }
}
