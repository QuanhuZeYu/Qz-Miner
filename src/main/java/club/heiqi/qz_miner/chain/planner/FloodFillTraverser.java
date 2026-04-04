package club.heiqi.qz_miner.chain.planner;

import net.minecraft.block.Block;

/**
 * 洪泛遍历器。
 */
public class FloodFillTraverser implements ChainTraverser {

    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    @Override
    public void seed(ChainSearchContext context) {
        ChainTarget origin = context.getOrigin();
        context.getVisited().add(origin);
        for (int[] offset : NEIGHBOR_OFFSETS) {
            ChainTarget neighbor = new ChainTarget(origin.getX() + offset[0], origin.getY() + offset[1], origin.getZ() + offset[2]);
            if (!context.getVisited().add(neighbor)) {
                continue;
            }

            Block neighborBlock = context.getWorld().getBlock(neighbor.getX(), neighbor.getY(), neighbor.getZ());
            if (neighborBlock != context.getSampleBlock()) {
                continue;
            }

            int neighborMeta = context.getWorld().getBlockMetadata(neighbor.getX(), neighbor.getY(), neighbor.getZ());
            if (neighborMeta != context.getSampleMeta()) {
                continue;
            }

            context.getCurrentFrontier().add(neighbor);
        }
    }

    @Override
    public boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        return ChainSearchAlgorithm.step(context, maxNodes, matcher, consumer);
    }
}
