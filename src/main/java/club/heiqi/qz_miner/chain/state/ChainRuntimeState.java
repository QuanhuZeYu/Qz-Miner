package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/** 持有本轮 traverser 候选队列与规划订阅，清理时协作注销并释放队列。 */
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

    /** 生命周期收口时注销规划订阅并清空 traversal 队列。 */
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
