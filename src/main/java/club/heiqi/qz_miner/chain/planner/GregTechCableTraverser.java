package club.heiqi.qz_miner.chain.planner;

import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/** 按 GT 真实连接关系规划；完整预检与普通 CHAIN 的目标上限短路分开。 */
public class GregTechCableTraverser implements BudgetedChainTraverser {
    private final ChainSession session;
    private final TraversalTarget target = new TraversalTarget(true);
    private final ConnectedNeighbors neighbors = new ConnectedNeighbors();
    private TraversalPhase phase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
    private ChainTarget seedTarget;

    public GregTechCableTraverser(ChainSession session) {
        this.session = session;
    }

    @Override
    public void seed(ChainSearchContext context) {
        reset();
        seedTarget = context.getOrigin();
        if (seedTarget != null) {
            context.getVisited().add(seedTarget);
            // 左键已取消原版破坏，origin 仍参与替换；破坏后 CHAIN 则只使用冻结种子扩展。
            // 所属合同：specs/mining-lifecycle.md。seed 不提前读取世界。
            phase = TraversalPhase.SEED_ORIGIN;
        }
    }

    @Override
    public TraversalStepResult step(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (context == null || control == null || matcher == null || consumer == null) {
            return TraversalStepResult.COMPLETED;
        }
        while (true) {
            TraversalStepResult stopped = TraversalBudget.observe(control);
            if (stopped != TraversalStepResult.CONTINUE) {
                return stopped;
            }
            TraversalStepResult result = advance(context, control, matcher, consumer);
            if (result != TraversalStepResult.CONTINUE) {
                return result;
            }
        }
    }

    private TraversalStepResult advance(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        switch (phase) {
            case SEED_ORIGIN:
                return readSeed(context, control);
            case FILTER_SEED_ORIGIN:
                return filterSeed(context, control);
            case ROTATE_FRONTIER:
                return rotateFrontier(context, control);
            case GENERATE_NEIGHBORS:
                return generateNeighbors(context, control);
            case CAPTURE_CURRENT_CONNECTIONS:
                return captureConnections(context, control);
            default:
                return admitCurrentTarget(context, control, matcher, consumer);
        }
    }

    private TraversalStepResult readSeed(ChainSearchContext context, ParallelTickControl control) {
        PlanningCandidateGate.CommitResult result = context.tryCommitPlanningCandidate(control, seedTarget);
        TraversalStepResult stopped = PlanningCandidateGate.commitStep(result, control);
        if (stopped != null) {
            return stopped;
        }
        if (result == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
            seedTarget = null;
            phase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        } else {
            phase = TraversalPhase.FILTER_SEED_ORIGIN;
        }
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult filterSeed(ChainSearchContext context, ParallelTickControl control) {
        PlanningCandidateGate.FilterResult result = context.tryCommitPlanningCandidateFilter(control, seedTarget);
        TraversalStepResult stopped = PlanningCandidateGate.filterStep(result, control);
        if (stopped != null) {
            return stopped;
        }
        if (result == PlanningCandidateGate.FilterResult.ACCEPTED) {
            context.getCurrentFrontier().add(seedTarget);
        }
        seedTarget = null;
        phase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult admitCurrentTarget(ChainSearchContext context, ParallelTickControl control,
            ChainTargetMatcher matcher, ChainTargetConsumer consumer) {
        if (target.isEmpty() && context.getCurrentFrontier().isEmpty()) {
            return finishCurrentFrontier(context, control);
        }
        TraversalStepResult result = target.advance(context, control, matcher, consumer);
        if (result != TraversalStepResult.CONTINUE) {
            return result;
        }
        if (target.isAccepted()) {
            neighbors.begin(target.get());
            target.clear();
            phase = TraversalPhase.GENERATE_NEIGHBORS;
        } else if (target.isReadyToSubmit()) {
            // 只有第 N+1 个真实匹配目标才能证明截断；达到 N 后仍须扩展及重验完整连接。
            if (context.getConfirmedCount() >= context.getMaxTargets()) {
                context.markTargetLimitExceeded();
                reset();
                return TraversalStepResult.COMPLETED;
            }
            phase = TraversalPhase.CAPTURE_CURRENT_CONNECTIONS;
        }
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult captureConnections(ChainSearchContext context, ParallelTickControl control) {
        if (control.shouldYield()) {
            return PlanningCandidateGate.yieldOrTerminate(control);
        }
        rememberConnectedSides(context, target.get());
        phase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        return TraversalStepResult.CONTINUE;
    }

    private TraversalStepResult generateNeighbors(ChainSearchContext context, ParallelTickControl control) {
        TraversalStepResult result = neighbors.step(context, control);
        if (result == TraversalStepResult.CONTINUE) {
            phase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        }
        return result;
    }

    private TraversalStepResult rotateFrontier(ChainSearchContext context, ParallelTickControl control) {
        TraversalStepResult result = TraversalBudget.rotateFrontier(context, control);
        if (result == TraversalStepResult.CONTINUE) {
            phase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
        }
        return result;
    }

    private TraversalStepResult finishCurrentFrontier(ChainSearchContext context, ParallelTickControl control) {
        if (control.shouldYield()) {
            return PlanningCandidateGate.yieldOrTerminate(control);
        }
        if (!context.getNextFrontier().isEmpty()) {
            phase = TraversalPhase.ROTATE_FRONTIER;
            return TraversalStepResult.CONTINUE;
        }
        reset();
        return TraversalStepResult.COMPLETED;
    }

    private void reset() {
        target.clear();
        neighbors.clear();
        seedTarget = null;
        phase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
    }

    private void rememberConnectedSides(ChainSearchContext context, ChainTarget current) {
        if (session == null || current == null) {
            return;
        }
        TileEntity tile = context.getWorld().getTileEntity(current.getX(), current.getY(), current.getZ());
        List<ForgeDirection> sides = CompatAdapters.cable().captureConnectedSides(tile);
        if (!sides.isEmpty()) {
            GregTechCableSessionState.rememberReconnectSides(session, current, sides);
        }
    }

    /** 连接方向由现有 GT adapter 提供，双向连接判定与方向顺序均不在遍历器重建。 */
    private final class ConnectedNeighbors extends TraversalNeighbors {
        private List<ForgeDirection> neighborDirections = Collections.emptyList();
        private int index;

        @Override
        protected void resetCursor() {
            index = 0;
            neighborDirections = center == null ? Collections.emptyList() : null;
        }

        @Override
        protected TraversalStepResult prepare(ChainSearchContext context, ParallelTickControl control) {
            if (neighborDirections != null) {
                return TraversalStepResult.CONTINUE;
            }
            if (control.shouldYield()) {
                return PlanningCandidateGate.yieldOrTerminate(control);
            }
            neighborDirections = resolveConnectedDirections(context);
            context.recordDurableProgress();
            return TraversalStepResult.CONTINUE;
        }

        @Override protected boolean hasNext() { return center != null && index < neighborDirections.size(); }
        @Override protected void advanceCursor() { index++; }

        @Override
        protected ChainTarget currentNeighbor() {
            ForgeDirection side = neighborDirections.get(index);
            if (side == null || side == ForgeDirection.UNKNOWN) {
                return null;
            }
            return new ChainTarget(center.getX() + side.offsetX, center.getY() + side.offsetY,
                    center.getZ() + side.offsetZ);
        }

        private List<ForgeDirection> resolveConnectedDirections(ChainSearchContext context) {
            if (context.getWorld() == null || center == null) {
                return Collections.emptyList();
            }
            TileEntity tile = context.getWorld().getTileEntity(center.getX(), center.getY(), center.getZ());
            return CompatAdapters.cable().isCable(tile)
                    ? CompatAdapters.cable().getConnectedSides(tile) : Collections.emptyList();
        }
    }

    private enum TraversalPhase {
        SEED_ORIGIN, FILTER_SEED_ORIGIN, PROCESS_CURRENT_FRONTIER,
        CAPTURE_CURRENT_CONNECTIONS, GENERATE_NEIGHBORS, ROTATE_FRONTIER
    }
}
