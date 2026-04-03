package club.heiqi.qz_miner.chain.planner;

import java.util.Arrays;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * 共用连锁搜索算法。
 *
 * 使用从中心向外扩散的 BFS 方式增量搜索。
 */
public final class ChainSearchAlgorithm {

    private static final List<ChainTarget> NEIGHBOR_OFFSETS = Arrays.asList(
        new ChainTarget(1, 0, 0),
        new ChainTarget(-1, 0, 0),
        new ChainTarget(0, 1, 0),
        new ChainTarget(0, -1, 0),
        new ChainTarget(0, 0, 1),
        new ChainTarget(0, 0, -1));
    private static final ChainTargetConsumer NO_OP_CONSUMER = target -> {};

    private ChainSearchAlgorithm() {}

    public static boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher) {
        return step(context, maxNodes, matcher, NO_OP_CONSUMER);
    }

    public static boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        int processed = 0;
        int matchedBefore = context.getMatched().size();
        context.rotateFrontier();

        while (processed < maxNodes && context.hasPendingTargets() && context.getMatched().size() < context.getMaxTargets()) {
            ChainTarget current = context.getCurrentTarget();
            if (current == null) {
                current = context.getCurrentFrontier().poll();
                context.setCurrentTarget(current);
            }

            if (current == null) {
                context.rotateFrontier();
                break;
            }

            for (ChainTarget offset : NEIGHBOR_OFFSETS) {
                ChainTarget next = new ChainTarget(
                    current.getX() + offset.getX(),
                    current.getY() + offset.getY(),
                    current.getZ() + offset.getZ());

                if (!context.getVisited().add(next)) {
                    continue;
                }

                if (getDistance(next, context.getOrigin()) > context.getMaxRadius()) {
                    continue;
                }

                Block block = context.getWorld().getBlock(next.getX(), next.getY(), next.getZ());
                if (block == null || block == Blocks.air || block != context.getSampleBlock()) {
                    continue;
                }

                int meta = context.getWorld().getBlockMetadata(next.getX(), next.getY(), next.getZ());
                if (meta != context.getSampleMeta()) {
                    continue;
                }

                if (!matcher.matches(next)) {
                    continue;
                }

                context.getMatched().add(next);
                context.getNextFrontier().add(next);
            }

            context.setCurrentTarget(null);
            consumer.accept(current);
            context.rotateFrontier();

            processed++;
        }

        if (processed > 0) {
            context.rotateFrontier();
        }

        boolean hasMore = !context.getCurrentFrontier().isEmpty() || !context.getNextFrontier().isEmpty();
        boolean foundNew = context.getMatched().size() > matchedBefore;

        return (hasMore || foundNew) && context.getMatched().size() < context.getMaxTargets();
    }

    private static int getDistance(ChainTarget a, ChainTarget b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, Math.max(dy, dz));
    }
}
