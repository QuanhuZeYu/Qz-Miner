package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * 盒扫遍历器。
 */
public class BoxScanTraverser implements ChainTraverser {

    /**
     * 初始化盒扫状态，但不一次性装填整盒候选点。
     *
     * @param context 搜索上下文
     */
    @Override
    public void seed(ChainSearchContext context) {
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

    /**
     * 将下一层外壳中的同类方块装入当前候选队列。
     *
     * @param context 搜索上下文
     * @return 是否仍存在可继续扫描的壳层
     */
    private boolean enqueueNextShell(ChainSearchContext context) {
        int nextDepth = context.getScanDepth() + 1;
        if (nextDepth > context.getMaxRadius()) {
            return false;
        }

        context.setScanDepth(nextDepth);

        ChainTarget origin = context.getOrigin();
        for (int x = origin.getX() - nextDepth; x <= origin.getX() + nextDepth; x++) {
            for (int y = origin.getY() - nextDepth; y <= origin.getY() + nextDepth; y++) {
                for (int z = origin.getZ() - nextDepth; z <= origin.getZ() + nextDepth; z++) {
                    if (!isOnShell(origin, nextDepth, x, y, z)) {
                        continue;
                    }

                    ChainTarget candidate = new ChainTarget(x, y, z);
                    if (!context.getVisited().add(candidate)) {
                        continue;
                    }

                    Block block = context.getWorld().getBlock(x, y, z);
                    if (block == null || block == Blocks.air || block != context.getSampleBlock()) {
                        continue;
                    }

                    int meta = context.getWorld().getBlockMetadata(x, y, z);
                    if (meta != context.getSampleMeta()) {
                        continue;
                    }

                    context.getCurrentFrontier().add(candidate);
                }
            }
        }

        return true;
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
