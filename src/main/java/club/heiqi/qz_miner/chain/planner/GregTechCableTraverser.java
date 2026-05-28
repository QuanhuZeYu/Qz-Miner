package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 按 GT 线缆真实连接关系遍历。
 */
public class GregTechCableTraverser implements ChainTraverser {

    private final ChainSession session;

    public GregTechCableTraverser(ChainSession session) {
        this.session = session;
    }

    @Override
    public void seed(ChainSearchContext context) {
        ChainTarget origin = context.getOrigin();
        if (origin == null) {
            return;
        }

        rememberConnectedSides(context, origin);
        context.getVisited().add(origin);
        for (ChainTarget neighbor : resolveConnectedNeighbors(context, origin)) {
            if (!context.getVisited().add(neighbor)) {
                continue;
            }
            if (!context.canTraverse(neighbor)) {
                continue;
            }
            context.getCurrentFrontier().add(neighbor);
        }
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

            if (!context.canTraverse(current) || !matcher.matches(current)) {
                processed++;
                continue;
            }

            rememberConnectedSides(context, current);
            consumer.accept(current);
            context.incrementConfirmedCount();
            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                processed++;
                break;
            }

            for (ChainTarget next : resolveConnectedNeighbors(context, current)) {
                if (!context.getVisited().add(next)) {
                    continue;
                }
                if (!context.canTraverse(next)) {
                    continue;
                }
                if (getDistance(next, context.getOrigin()) > context.getMaxRadius()) {
                    continue;
                }
                context.getNextFrontier().add(next);
            }

            processed++;
        }

        if (context.getCurrentFrontier().isEmpty() && !context.getNextFrontier().isEmpty()) {
            while (!context.getNextFrontier().isEmpty()) {
                context.getCurrentFrontier().add(context.getNextFrontier().poll());
            }
        }

        return !context.getCurrentFrontier().isEmpty();
    }

    private void rememberConnectedSides(ChainSearchContext context, ChainTarget target) {
        if (session == null || context == null || target == null) {
            return;
        }

        TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
        List<ForgeDirection> connectedSides = CompatAdapters.cable().captureConnectedSides(tileEntity);
        if (!connectedSides.isEmpty()) {
            GregTechCableSessionState.rememberReconnectSides(session, target, connectedSides);
        }
    }

    private List<ChainTarget> resolveConnectedNeighbors(ChainSearchContext context, ChainTarget source) {
        TileEntity tileEntity = context.getWorld().getTileEntity(source.getX(), source.getY(), source.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return java.util.Collections.emptyList();
        }

        List<ChainTarget> neighbors = new ArrayList<ChainTarget>();
        for (ForgeDirection side : CompatAdapters.cable().getConnectedSides(tileEntity)) {
            neighbors.add(new ChainTarget(
                source.getX() + side.offsetX,
                source.getY() + side.offsetY,
                source.getZ() + side.offsetZ));
        }
        return neighbors;
    }

    private int getDistance(ChainTarget a, ChainTarget b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, Math.max(dy, dz));
    }
}
