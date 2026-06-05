package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 区段清理预算化遍历器：固定清理被挖方块所在的 16x16x16 区段。
 */
public class SectionClearTraverser implements BudgetedChainTraverser {

    private static final int SECTION_SIZE = 16;

    private int minX;
    private int maxX;
    private int minY;
    private int maxY;
    private int minZ;
    private int maxZ;
    private int maxDepth;
    private int enqueueDepth;
    private int enqueueX;
    private int enqueueY;
    private int enqueueZ;
    private boolean shellEnqueueInProgress;
    private ChainTarget currentTarget;

    /**
     * 构造区段清理遍历器。
     */
    public SectionClearTraverser() {}

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
        resetEnqueueState();
        currentTarget = null;

        context.getVisited().add(origin);
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

    /**
     * 按预算推进区段壳层扫描。
     *
     * @param context 搜索上下文
     * @param control 当前并行 Tick 控制对象
     * @return 当前预算化装填结果
     */
    private TraversalStepResult enqueueNextShell(ChainSearchContext context, ParallelTickControl control) {
        if (!shellEnqueueInProgress) {
            int nextDepth = context.getScanDepth() + 1;
            if (nextDepth > maxDepth) {
                return TraversalStepResult.COMPLETED;
            }
            beginShellEnqueue(context, nextDepth);
        }

        ChainTarget origin = context.getOrigin();
        int startX = Math.max(minX, origin.getX() - enqueueDepth);
        int endX = Math.min(maxX, origin.getX() + enqueueDepth);
        int startY = Math.max(minY, origin.getY() - enqueueDepth);
        int endY = Math.min(maxY, origin.getY() + enqueueDepth);
        int startZ = Math.max(minZ, origin.getZ() - enqueueDepth);
        int endZ = Math.min(maxZ, origin.getZ() + enqueueDepth);

        for (int x = enqueueX; x <= endX; x++) {
            int yStart = x == enqueueX ? enqueueY : startY;
            for (int y = yStart; y <= endY; y++) {
                int zStart = x == enqueueX && y == yStart ? enqueueZ : startZ;
                for (int z = zStart; z <= endZ; z++) {
                    if (!control.tryConsumeWork(1)) {
                        saveCursor(x, y, z);
                        return yieldOrTerminate(control);
                    }

                    if (!isOnShell(origin, enqueueDepth, x, y, z)) {
                        continue;
                    }

                    ChainTarget candidate = new ChainTarget(x, y, z);
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

        context.setScanDepth(enqueueDepth);
        resetEnqueueState();
        return TraversalStepResult.CONTINUE;
    }

    private void beginShellEnqueue(ChainSearchContext context, int nextDepth) {
        ChainTarget origin = context.getOrigin();
        enqueueDepth = nextDepth;
        enqueueX = Math.max(minX, origin.getX() - nextDepth);
        enqueueY = Math.max(minY, origin.getY() - nextDepth);
        enqueueZ = Math.max(minZ, origin.getZ() - nextDepth);
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
    }

    private boolean hasMoreShellWork(ChainSearchContext context) {
        return shellEnqueueInProgress || context.getScanDepth() < maxDepth;
    }

    private TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return control.isCancelRequested() ? TraversalStepResult.TERMINATED : TraversalStepResult.YIELDED;
    }

    /**
     * 计算坐标所在区段的起点。
     *
     * @param coord 世界坐标
     * @return 对齐后的区段起点
     */
    private static int alignToSection(int coord) {
        return (coord >> 4) << 4;
    }

    /**
     * 计算从原点扩张到覆盖整个区段所需的最大壳层半径。
     *
     * @param origin 搜索原点
     * @return 最大壳层半径
     */
    private int resolveMaxDepth(ChainTarget origin) {
        int depthX = Math.max(origin.getX() - minX, maxX - origin.getX());
        int depthY = Math.max(origin.getY() - minY, maxY - origin.getY());
        int depthZ = Math.max(origin.getZ() - minZ, maxZ - origin.getZ());
        return Math.max(depthX, Math.max(depthY, depthZ));
    }

    /**
     * 判断坐标是否位于当前壳层表面。
     *
     * @param origin 搜索原点
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
