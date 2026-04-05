package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * 伐木壳层遍历器。
 */
public class LoggingFloodFillTraverser implements ChainTraverser {

    private final List<ChainTarget> neighborOffsets;

    public LoggingFloodFillTraverser(int shellLayers) {
        this.neighborOffsets = createNeighborOffsets(shellLayers);
    }

    @Override
    public void seed(ChainSearchContext context) {
        enqueueNeighbors(context, context.getOrigin());
    }

    @Override
    public boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        int processed = 0;

        if (context.getConfirmedCount() >= context.getMaxTargets()) {
            return false;
        }

        while (processed < maxNodes && !context.getCurrentFrontier().isEmpty()) {
            ChainTarget current = context.getCurrentFrontier().poll();
            if (current == null) {
                break;
            }

            if (!context.canTraverse(current)) {
                processed++;
                continue;
            }

            if (!matcher.matches(current)) {
                processed++;
                continue;
            }

            consumer.accept(current);
            context.incrementConfirmedCount();

            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                processed++;
                break;
            }

            enqueueNeighbors(context, current);
            processed++;
        }

        if (context.getCurrentFrontier().isEmpty() && !context.getNextFrontier().isEmpty()) {
            while (!context.getNextFrontier().isEmpty()) {
                context.getCurrentFrontier().add(context.getNextFrontier().poll());
            }
        }

        return !context.getCurrentFrontier().isEmpty();
    }

    private void enqueueNeighbors(ChainSearchContext context, ChainTarget center) {
        for (ChainTarget offset : neighborOffsets) {
            ChainTarget next = new ChainTarget(
                center.getX() + offset.getX(),
                center.getY() + offset.getY(),
                center.getZ() + offset.getZ());

            if (!context.getVisited().add(next)) {
                continue;
            }

            if (!context.canTraverse(next)) {
                continue;
            }

            context.getNextFrontier().add(next);
        }
    }

    private static List<ChainTarget> createNeighborOffsets(int shellLayers) {
        int resolvedShellLayers = Math.max(1, shellLayers);
        List<ChainTarget> offsets = new ArrayList<ChainTarget>();
        for (int dx = -resolvedShellLayers; dx <= resolvedShellLayers; dx++) {
            for (int dy = -resolvedShellLayers; dy <= resolvedShellLayers; dy++) {
                for (int dz = -resolvedShellLayers; dz <= resolvedShellLayers; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) > resolvedShellLayers) {
                        continue;
                    }

                    offsets.add(new ChainTarget(dx, dy, dz));
                }
            }
        }
        return offsets;
    }
}
