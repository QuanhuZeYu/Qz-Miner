package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/**
 * 单次连锁运行时状态。
 *
 * <p>阶段8 块3 瘦身：删除旧链路依赖的 5 字段（pendingBreakTargets/plannerRunning/plannerCompleted/
 * matchedTargetCount/nextExecutorAllowedMillis）+ stopExecutionPreservingDrops 三层死代码。
 * 新链路的目标队列由 {@link club.heiqi.qz_miner.chain.execution.ChainExecutionContext} 承载，
 * 节流戳由 context.nextExecutorAllowedMillis 承载，matchedCount 由 PlanCompleted 事件承载。
 * 本类仅保留 traversalTargets（ChainPlanningRuntimeFactory 装配 traverser 用）+ plannerSubscription
 * （规划订阅句柄，clear 时摘除）。</p>
 */
public final class ChainRuntimeState {

    private final UUID playerUUID;
    private volatile ParallelTickSubscription plannerSubscription;
    private final ConcurrentLinkedQueue<ChainTarget> traversalTargets = new ConcurrentLinkedQueue<ChainTarget>();

    public ChainRuntimeState(UUID playerUUID) {
        this.playerUUID = playerUUID;
    }

    public ParallelTickSubscription getPlannerSubscription() {
        return plannerSubscription;
    }

    public void setPlannerSubscription(ParallelTickSubscription plannerSubscription) {
        if (this.plannerSubscription == null && plannerSubscription != null) {
            MyMod.LOG.debug("[ChainRuntime] Player {} plannerSubscription attached", playerUUID);
        } else if (this.plannerSubscription != null && plannerSubscription == null) {
            MyMod.LOG.debug("[ChainRuntime] Player {} plannerSubscription cleared", playerUUID);
        }
        this.plannerSubscription = plannerSubscription;
    }

    public ConcurrentLinkedQueue<ChainTarget> getTraversalTargets() {
        return traversalTargets;
    }

    /**
     * 清理运行时状态（生命周期收口时调用）。
     *
     * <p>摘除规划订阅句柄 + 清空 traversal 队列。阶段8 块3 后不再持有 pendingBreak/matched/throttle 等
     * 旧字段（已迁移到 ChainExecutionContext）。</p>
     *
     * @param reason 清理原因
     */
    public void clear(String reason) {
        if (plannerSubscription != null) {
            plannerSubscription.unregister();
            setPlannerSubscription(null);
        }
        traversalTargets.clear();
        MyMod.LOG.debug("[ChainRuntime] Cleared runtime state for player {}, reason={}",
            playerUUID, reason);
    }
}
