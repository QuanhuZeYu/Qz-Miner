package club.heiqi.qz_miner.chain.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 按 GT 线缆真实连接关系遍历。
 */
public class GregTechCableTraverser implements BudgetedChainTraverser {

    private final ChainSession session;
    private TraversalPhase budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
    private ChainTarget currentTarget;
    private ChainTarget pendingNeighbor;
    private List<ForgeDirection> neighborDirections = Collections.emptyList();
    private int neighborIndex;

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
                resetBudgetState();
                return TraversalStepResult.COMPLETED;
            }
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            if (budgetPhase == TraversalPhase.ROTATE_FRONTIER) {
                TraversalStepResult rotateResult = rotateFrontier(context, control);
                if (rotateResult != TraversalStepResult.CONTINUE) {
                    return rotateResult;
                }
                continue;
            }

            if (budgetPhase == TraversalPhase.GENERATE_NEIGHBORS) {
                TraversalStepResult neighborResult = generateConnectedNeighbors(context, control);
                if (neighborResult != TraversalStepResult.CONTINUE) {
                    return neighborResult;
                }
                continue;
            }

            if (currentTarget == null) {
                if (context.getCurrentFrontier().isEmpty()) {
                    if (!control.tryConsumeWork(1)) {
                        return yieldOrTerminate(control);
                    }
                    if (!context.getNextFrontier().isEmpty()) {
                        budgetPhase = TraversalPhase.ROTATE_FRONTIER;
                        continue;
                    }
                    resetBudgetState();
                    return TraversalStepResult.COMPLETED;
                }

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
            if (!context.canTraverse(currentTarget)) {
                clearCurrentTarget();
                continue;
            }

            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            if (!matcher.matches(currentTarget)) {
                clearCurrentTarget();
                continue;
            }

            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            rememberConnectedSides(context, currentTarget);

            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            if (control.isCancelRequested()) {
                return TraversalStepResult.TERMINATED;
            }
            consumer.accept(currentTarget);
            context.incrementConfirmedCount();
            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                clearCurrentTarget();
                return TraversalStepResult.COMPLETED;
            }

            budgetPhase = TraversalPhase.GENERATE_NEIGHBORS;
            neighborDirections = null;
            neighborIndex = 0;
            pendingNeighbor = null;
        }
    }

    private TraversalStepResult rotateFrontier(ChainSearchContext context, ParallelTickControl control) {
        while (!context.getNextFrontier().isEmpty()) {
            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            ChainTarget target = context.getNextFrontier().poll();
            if (target != null) {
                context.getCurrentFrontier().add(target);
            }
        }
        budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult generateConnectedNeighbors(ChainSearchContext context, ParallelTickControl control) {
        if (currentTarget == null) {
            clearCurrentTarget();
            return TraversalStepResult.CONTINUE;
        }

        if (neighborDirections == null) {
            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            neighborDirections = resolveConnectedDirections(context, currentTarget);
            neighborIndex = 0;
        }

        while (neighborIndex < neighborDirections.size() || pendingNeighbor != null) {
            if (control.isCancelRequested()) {
                return TraversalStepResult.TERMINATED;
            }
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            if (pendingNeighbor != null) {
                if (!control.tryConsumeWork(1)) {
                    return yieldOrTerminate(control);
                }
                ChainTarget pending = pendingNeighbor;
                pendingNeighbor = null;
                neighborIndex++;
                if (!context.getVisited().add(pending)) {
                    continue;
                }
                if (context.canTraverse(pending)) {
                    context.getNextFrontier().add(pending);
                }
                continue;
            }

            if (!control.tryConsumeWork(1)) {
                return yieldOrTerminate(control);
            }
            ForgeDirection side = neighborDirections.get(neighborIndex);
            if (side == null || side == ForgeDirection.UNKNOWN) {
                neighborIndex++;
                continue;
            }

            ChainTarget next = new ChainTarget(
                currentTarget.getX() + side.offsetX,
                currentTarget.getY() + side.offsetY,
                currentTarget.getZ() + side.offsetZ);
            if (context.getVisited().contains(next)
                || getDistance(next, context.getOrigin()) > context.getMaxRadius()
                || context.getConfirmedCount() >= context.getMaxTargets()) {
                neighborIndex++;
                continue;
            }

            pendingNeighbor = next;
        }

        clearCurrentTarget();
        return TraversalStepResult.CONTINUE;
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
        List<ChainTarget> neighbors = new ArrayList<ChainTarget>();
        for (ForgeDirection side : resolveConnectedDirections(context, source)) {
            neighbors.add(new ChainTarget(
                source.getX() + side.offsetX,
                source.getY() + side.offsetY,
                source.getZ() + side.offsetZ));
        }
        return neighbors;
    }

    private List<ForgeDirection> resolveConnectedDirections(ChainSearchContext context, ChainTarget source) {
        TileEntity tileEntity = context.getWorld().getTileEntity(source.getX(), source.getY(), source.getZ());
        if (!CompatAdapters.cable().isCable(tileEntity)) {
            return Collections.emptyList();
        }
        return CompatAdapters.cable().getConnectedSides(tileEntity);
    }

    private TraversalStepResult yieldOrTerminate(ParallelTickControl control) {
        return control.isCancelRequested() ? TraversalStepResult.TERMINATED : TraversalStepResult.YIELDED;
    }

    private void clearCurrentTarget() {
        budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        currentTarget = null;
        pendingNeighbor = null;
        neighborDirections = Collections.emptyList();
        neighborIndex = 0;
    }

    private void resetBudgetState() {
        clearCurrentTarget();
        budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
    }

    private enum TraversalPhase {
        PROCESS_CURRENT_FRONTIER,
        GENERATE_NEIGHBORS,
        ROTATE_FRONTIER
    }

    private int getDistance(ChainTarget a, ChainTarget b) {
        int dx = Math.abs(a.getX() - b.getX());
        int dy = Math.abs(a.getY() - b.getY());
        int dz = Math.abs(a.getZ() - b.getZ());
        return Math.max(dx, Math.max(dy, dz));
    }
}
