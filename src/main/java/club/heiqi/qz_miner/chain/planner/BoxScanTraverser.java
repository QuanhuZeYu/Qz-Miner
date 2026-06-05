package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.parallel.ParallelTickControl;

/**
 * 盒扫遍历器。
 */
public class BoxScanTraverser implements BudgetedChainTraverser {

    private static final int MAX_SCAN_COORDINATES_PER_BATCH = 256;

    private int enqueueDepth;
    private int enqueueX;
    private int enqueueY;
    private int enqueueZ;
    private boolean shellEnqueueInProgress;
    private ChainTarget currentTarget;

    /**
     * 初始化盒扫状态，但不一次性装填整盒候选点。
     *
     * @param context 搜索上下文
     */
    @Override
    public void seed(ChainSearchContext context) {
        resetEnqueueState();
        currentTarget = null;
        context.getVisited().add(context.getOrigin());
        context.setScanDepth(0);
    }

    /**
     * 分片消费预装填完成的盒扫候选目标。
     *
     * @param context 搜索上下文
     * @param maxNodes 本轮最多处理节点数
     * @param matcher 目标匹配器
     * @param consumer 已确认目标消费者
     * @return 是否还有剩余候选目标
     */
    @Override
    public boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        int processed = 0;

        if (context.getConfirmedCount() >= context.getMaxTargets()) {
            return false;
        }

        while (processed < maxNodes) {
            if (context.getCurrentFrontier().isEmpty()) {
                if (!enqueueNextShell(context)) {
                    return false;
                }
                if (context.getCurrentFrontier().isEmpty()) {
                    return hasMoreShellWork(context);
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
            && (!context.getCurrentFrontier().isEmpty() || context.getScanDepth() < context.getMaxRadius());
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
     * 将下一层外壳中的同类方块装入当前候选队列。
     *
     * @param context 搜索上下文
     * @return 是否仍存在可继续扫描的壳层
     */
    private boolean enqueueNextShell(ChainSearchContext context) {
        if (context == null || context.getOrigin() == null) {
            return false;
        }

        if (!shellEnqueueInProgress) {
            int nextDepth = context.getScanDepth() + 1;
            if (nextDepth > context.getMaxRadius()) {
                return false;
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
        int scannedCoordinates = 0;

        for (int x = enqueueX; x <= maxX; x++) {
            int yStart = x == enqueueX ? enqueueY : minY;
            for (int y = yStart; y <= maxY; y++) {
                int zStart = x == enqueueX && y == yStart ? enqueueZ : minZ;
                for (int z = zStart; z <= maxZ; z++) {
                    scannedCoordinates++;

                    if (!isOnShell(origin, enqueueDepth, x, y, z)) {
                        if (scannedCoordinates >= MAX_SCAN_COORDINATES_PER_BATCH) {
                            advanceCursorAfterCoordinate(context, origin, x, y, z, maxX, maxY, minZ, maxZ);
                            return true;
                        }
                        continue;
                    }

                    ChainTarget candidate = new ChainTarget(x, y, z);
                    if (!context.getVisited().add(candidate)) {
                        continue;
                    }

                    if (!context.canTraverse(candidate)) {
                        if (scannedCoordinates >= MAX_SCAN_COORDINATES_PER_BATCH) {
                            advanceCursorAfterCoordinate(context, origin, x, y, z, maxX, maxY, minZ, maxZ);
                            return true;
                        }
                        continue;
                    }

                    context.getCurrentFrontier().add(candidate);
                    if (scannedCoordinates >= MAX_SCAN_COORDINATES_PER_BATCH) {
                        advanceCursorAfterCoordinate(context, origin, x, y, z, maxX, maxY, minZ, maxZ);
                        return true;
                    }
                }
            }
        }

        context.setScanDepth(enqueueDepth);
        resetEnqueueState();
        return true;
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
            beginShellEnqueue(context, nextDepth);
        }

        ChainTarget origin = context.getOrigin();
        int minX = origin.getX() - enqueueDepth;
        int maxX = origin.getX() + enqueueDepth;
        int minY = origin.getY() - enqueueDepth;
        int maxY = origin.getY() + enqueueDepth;
        int minZ = origin.getZ() - enqueueDepth;
        int maxZ = origin.getZ() + enqueueDepth;

        for (int x = enqueueX; x <= maxX; x++) {
            int yStart = x == enqueueX ? enqueueY : minY;
            for (int y = yStart; y <= maxY; y++) {
                int zStart = x == enqueueX && y == yStart ? enqueueZ : minZ;
                for (int z = zStart; z <= maxZ; z++) {
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
        enqueueX = origin.getX() - nextDepth;
        enqueueY = origin.getY() - nextDepth;
        enqueueZ = origin.getZ() - nextDepth;
        shellEnqueueInProgress = true;
    }

    private void advanceCursorAfterCoordinate(ChainSearchContext context, ChainTarget origin, int x, int y, int z, int maxX, int maxY, int minZ, int maxZ) {
        int nextX = x;
        int nextY = y;
        int nextZ = z + 1;
        if (nextZ > maxZ) {
            nextZ = minZ;
            nextY++;
            if (nextY > maxY) {
                nextY = origin.getY() - enqueueDepth;
                nextX++;
            }
        }

        if (nextX > maxX) {
            context.setScanDepth(enqueueDepth);
            resetEnqueueState();
            return;
        }

        enqueueX = nextX;
        enqueueY = nextY;
        enqueueZ = nextZ;
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
        return shellEnqueueInProgress || context.getScanDepth() < context.getMaxRadius();
    }

    private TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return control.isCancelRequested() ? TraversalStepResult.TERMINATED : TraversalStepResult.YIELDED;
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
