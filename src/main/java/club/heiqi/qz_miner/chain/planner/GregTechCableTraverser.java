package club.heiqi.qz_miner.chain.planner;

import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.parallel.ParallelTickControl;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 按 GT 线缆真实连接关系预算化遍历。
 * 与 FloodFill 系不同，本遍历器 origin 参与连锁（左键取消原版破坏，origin 仍在世界）。
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
        resetBudgetState();
        ChainTarget origin = context.getOrigin();
        if (origin == null) {
            return;
        }

        context.getVisited().add(origin);
        // GT 线缆左键触发（LeftClickObserved）取消了原版破坏，origin 仍在世界，
        // 必须参与统一替换。这与 FloodFill 系（CHAIN/AREA 走破坏后事件、origin 已被
        // 原版破坏而排除）语义相反——见 docs/反馈层/决策/chain-origin-inclusion-semantics.md。
        // seed 只保存可恢复游标；origin 的世界读取与 candidate 判定下沉到首次 step。
        currentTarget = origin;
        budgetPhase = TraversalPhase.SEED_ORIGIN;
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
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            if (budgetPhase == TraversalPhase.SEED_ORIGIN) {
                PlanningCandidateGate.CommitResult candidateResult =
                        context.tryCommitPlanningCandidate(control, currentTarget);
                if (candidateResult == PlanningCandidateGate.CommitResult.YIELDED) {
                    return TraversalStepResult.YIELDED;
                }
                if (candidateResult == PlanningCandidateGate.CommitResult.TERMINATED) {
                    return TraversalStepResult.TERMINATED;
                }

                if (candidateResult == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
                    currentTarget = null;
                    budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
                    continue;
                }
                budgetPhase = TraversalPhase.FILTER_SEED_ORIGIN;
                continue;
            }

            if (budgetPhase == TraversalPhase.FILTER_SEED_ORIGIN) {
                PlanningCandidateGate.FilterResult filterResult =
                        context.tryCommitPlanningCandidateFilter(control, currentTarget);
                if (filterResult == PlanningCandidateGate.FilterResult.YIELDED) {
                    return TraversalStepResult.YIELDED;
                }
                if (filterResult == PlanningCandidateGate.FilterResult.TERMINATED) {
                    return TraversalStepResult.TERMINATED;
                }
                ChainTarget origin = currentTarget;
                currentTarget = null;
                budgetPhase = TraversalPhase.PROCESS_CURRENT_FRONTIER;
                if (filterResult == PlanningCandidateGate.FilterResult.ACCEPTED) {
                    context.getCurrentFrontier().add(origin);
                }
                continue;
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

            if (budgetPhase == TraversalPhase.CHECK_CURRENT_FILTER) {
                if (currentTarget == null) {
                    clearCurrentTarget();
                    continue;
                }
                PlanningCandidateGate.FilterResult filterResult =
                        context.tryCommitPlanningCandidateFilter(control, currentTarget);
                if (filterResult == PlanningCandidateGate.FilterResult.YIELDED) {
                    return TraversalStepResult.YIELDED;
                }
                if (filterResult == PlanningCandidateGate.FilterResult.TERMINATED) {
                    return TraversalStepResult.TERMINATED;
                }
                if (filterResult == PlanningCandidateGate.FilterResult.REJECTED) {
                    clearCurrentTarget();
                    continue;
                }
                budgetPhase = TraversalPhase.CHECK_CURRENT_MATCHER;
                continue;
            }

            if (budgetPhase == TraversalPhase.CHECK_CURRENT_MATCHER) {
                if (currentTarget == null) {
                    clearCurrentTarget();
                    continue;
                }
                if (control.shouldYield()) {
                    return yieldOrTerminate(control);
                }
                if (!matcher.matches(currentTarget)) {
                    clearCurrentTarget();
                    continue;
                }
                if (context.getConfirmedCount() >= context.getMaxTargets()) {
                    context.markTargetLimitExceeded();
                    resetBudgetState();
                    return TraversalStepResult.COMPLETED;
                }
                budgetPhase = TraversalPhase.CAPTURE_CURRENT_CONNECTIONS;
                continue;
            }

            if (budgetPhase == TraversalPhase.CAPTURE_CURRENT_CONNECTIONS) {
                if (currentTarget == null) {
                    clearCurrentTarget();
                    continue;
                }
                if (control.shouldYield()) {
                    return yieldOrTerminate(control);
                }
                rememberConnectedSides(context, currentTarget);
                budgetPhase = TraversalPhase.SUBMIT_CURRENT_TARGET;
                continue;
            }

            if (budgetPhase == TraversalPhase.SUBMIT_CURRENT_TARGET) {
                if (currentTarget == null) {
                    clearCurrentTarget();
                    continue;
                }
                if (control.shouldYield()) {
                    return yieldOrTerminate(control);
                }
                if (control.isCancelRequested()) {
                    return TraversalStepResult.TERMINATED;
                }
                consumer.accept(currentTarget);
                context.incrementConfirmedCount();
                budgetPhase = TraversalPhase.GENERATE_NEIGHBORS;
                neighborDirections = null;
                neighborIndex = 0;
                continue;
            }

            if (currentTarget == null) {
                if (context.getCurrentFrontier().isEmpty()) {
                    if (control.shouldYield()) {
                        return yieldOrTerminate(control);
                    }
                    if (!context.getNextFrontier().isEmpty()) {
                        budgetPhase = TraversalPhase.ROTATE_FRONTIER;
                        continue;
                    }
                    resetBudgetState();
                    return TraversalStepResult.COMPLETED;
                }

                ChainTarget queuedTarget = context.getCurrentFrontier().peek();
                PlanningCandidateGate.CommitResult candidateResult =
                        context.tryCommitPlanningCandidate(control, queuedTarget);
                if (candidateResult == PlanningCandidateGate.CommitResult.YIELDED) {
                    return TraversalStepResult.YIELDED;
                }
                if (candidateResult == PlanningCandidateGate.CommitResult.TERMINATED) {
                    return TraversalStepResult.TERMINATED;
                }

                currentTarget = context.getCurrentFrontier().poll();
                if (currentTarget == null) {
                    continue;
                }
                if (candidateResult == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
                    clearCurrentTarget();
                    continue;
                }
                budgetPhase = TraversalPhase.CHECK_CURRENT_FILTER;
                continue;
            }
            budgetPhase = TraversalPhase.CHECK_CURRENT_FILTER;
        }
    }

    private TraversalStepResult rotateFrontier(ChainSearchContext context, ParallelTickControl control) {
        while (!context.getNextFrontier().isEmpty()) {
            if (control.shouldYield()) {
                return yieldOrTerminate(control);
            }
            ChainTarget target = context.getNextFrontier().poll();
            if (target != null) {
                context.getCurrentFrontier().add(target);
                context.recordDurableProgress();
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
            if (control.shouldYield()) {
                return yieldOrTerminate(control);
            }
            neighborDirections = resolveConnectedDirections(context, currentTarget);
            neighborIndex = 0;
            context.recordDurableProgress();
        }

        while (neighborIndex < neighborDirections.size()) {
            if (control.isCancelRequested()) {
                return TraversalStepResult.TERMINATED;
            }
            if (control.shouldYield()) {
                return TraversalStepResult.YIELDED;
            }

            if (pendingNeighbor != null) {
                PlanningCandidateGate.FilterResult filterResult =
                        context.tryCommitPlanningCandidateFilter(control, pendingNeighbor);
                if (filterResult == PlanningCandidateGate.FilterResult.YIELDED) {
                    return TraversalStepResult.YIELDED;
                }
                if (filterResult == PlanningCandidateGate.FilterResult.TERMINATED) {
                    return TraversalStepResult.TERMINATED;
                }
                ChainTarget committedNeighbor = pendingNeighbor;
                pendingNeighbor = null;
                neighborIndex++;
                if (context.getVisited().add(committedNeighbor)
                        && filterResult == PlanningCandidateGate.FilterResult.ACCEPTED) {
                    context.getNextFrontier().add(committedNeighbor);
                }
                continue;
            }

            ForgeDirection side = neighborDirections.get(neighborIndex);
            if (side == null || side == ForgeDirection.UNKNOWN) {
                if (control.shouldYield()) {
                    return yieldOrTerminate(control);
                }
                neighborIndex++;
                context.recordDurableProgress();
                continue;
            }

            ChainTarget next = new ChainTarget(
                currentTarget.getX() + side.offsetX,
                currentTarget.getY() + side.offsetY,
                currentTarget.getZ() + side.offsetZ);
            if (context.getVisited().contains(next)
                || getDistance(next, context.getOrigin()) > context.getMaxRadius()) {
                if (control.shouldYield()) {
                    return yieldOrTerminate(control);
                }
                neighborIndex++;
                context.recordDurableProgress();
                continue;
            }

            PlanningCandidateGate.CommitResult candidateResult =
                    context.tryCommitPlanningCandidate(control, next);
            if (candidateResult == PlanningCandidateGate.CommitResult.YIELDED) {
                return TraversalStepResult.YIELDED;
            }
            if (candidateResult == PlanningCandidateGate.CommitResult.TERMINATED) {
                return TraversalStepResult.TERMINATED;
            }

            if (candidateResult == PlanningCandidateGate.CommitResult.AIR_COMMITTED) {
                neighborIndex++;
                context.getVisited().add(next);
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

    private List<ForgeDirection> resolveConnectedDirections(ChainSearchContext context, ChainTarget source) {
        if (context == null || context.getWorld() == null || source == null) {
            return Collections.emptyList();
        }
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
        SEED_ORIGIN,
        FILTER_SEED_ORIGIN,
        PROCESS_CURRENT_FRONTIER,
        CHECK_CURRENT_FILTER,
        CHECK_CURRENT_MATCHER,
        CAPTURE_CURRENT_CONNECTIONS,
        SUBMIT_CURRENT_TARGET,
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
