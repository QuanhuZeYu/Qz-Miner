package club.heiqi.qz_miner.chain.planner;

/**
 * 区段清理遍历器：固定清理被挖方块所在的 16x16x16 区段。
 */
public class SectionClearTraverser implements ChainTraverser {

    private static final int SECTION_SIZE = 16;

    private int minX;
    private int maxX;
    private int minY;
    private int maxY;
    private int minZ;
    private int maxZ;
    private int maxDepth;

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

        context.getVisited().add(origin);
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
                if (!enqueueNextShell(context)) {
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
            && (!context.getCurrentFrontier().isEmpty() || context.getScanDepth() < maxDepth);
    }

    /**
     * 将区段中的下一层壳面加入候选队列。
     *
     * @param context 搜索上下文
     * @return 是否还存在未扫描的壳层
     */
    private boolean enqueueNextShell(ChainSearchContext context) {
        int nextDepth = context.getScanDepth() + 1;
        if (nextDepth > maxDepth) {
            return false;
        }

        context.setScanDepth(nextDepth);
        enqueueShell(context, nextDepth);
        return true;
    }

    /**
     * 将指定壳层上的候选点加入当前队列。
     *
     * @param context 搜索上下文
     * @param depth 当前壳层半径
     */
    private void enqueueShell(ChainSearchContext context, int depth) {
        ChainTarget origin = context.getOrigin();

        int startX = Math.max(minX, origin.getX() - depth);
        int endX = Math.min(maxX, origin.getX() + depth);
        int startY = Math.max(minY, origin.getY() - depth);
        int endY = Math.min(maxY, origin.getY() + depth);
        int startZ = Math.max(minZ, origin.getZ() - depth);
        int endZ = Math.min(maxZ, origin.getZ() + depth);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    if (!isOnShell(origin, depth, x, y, z)) {
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
