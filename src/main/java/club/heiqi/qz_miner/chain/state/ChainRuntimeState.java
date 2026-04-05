package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/**
 * 单次连锁运行时状态。
 */
public final class ChainRuntimeState {

    private final UUID playerUUID;
    private volatile ParallelTickSubscription plannerSubscription;
    private volatile ParallelTickSubscription executorSubscription;
    private final ConcurrentLinkedQueue<ChainTarget> traversalTargets = new ConcurrentLinkedQueue<ChainTarget>();
    private final ConcurrentLinkedQueue<ChainTarget> pendingBreakTargets = new ConcurrentLinkedQueue<ChainTarget>();
    private volatile boolean plannerRunning;
    private volatile boolean plannerCompleted;
    private volatile long plannerHeartbeatMillis;
    private volatile int matchedTargetCount;
    private final AtomicLong nextExecutorAllowedMillis = new AtomicLong();

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
            MyMod.LOG.debug("[ChainRuntime] Player {} plannerSubscription cleared queuedTargets={}",
                playerUUID, pendingBreakTargets.size());
        }
        this.plannerSubscription = plannerSubscription;
    }

    public ParallelTickSubscription getExecutorSubscription() {
        return executorSubscription;
    }

    public void setExecutorSubscription(ParallelTickSubscription executorSubscription) {
        if (this.executorSubscription == null && executorSubscription != null) {
            MyMod.LOG.debug("[ChainRuntime] Player {} executorSubscription attached", playerUUID);
        } else if (this.executorSubscription != null && executorSubscription == null) {
            MyMod.LOG.debug("[ChainRuntime] Player {} executorSubscription cleared queuedTargets={}",
                playerUUID, pendingBreakTargets.size());
        }
        this.executorSubscription = executorSubscription;
    }

    public ConcurrentLinkedQueue<ChainTarget> getTraversalTargets() {
        return traversalTargets;
    }

    public ConcurrentLinkedQueue<ChainTarget> getPendingBreakTargets() {
        return pendingBreakTargets;
    }

    public boolean isPlannerRunning() {
        return plannerRunning;
    }

    public void setPlannerRunning(boolean plannerRunning) {
        if (this.plannerRunning != plannerRunning) {
            MyMod.LOG.debug("[ChainRuntime] Player {} plannerRunning {} -> {} traversalTargets={} pendingBreakTargets={}",
                playerUUID, this.plannerRunning, plannerRunning, traversalTargets.size(), pendingBreakTargets.size());
        }
        this.plannerRunning = plannerRunning;
    }

    public boolean isPlannerCompleted() {
        return plannerCompleted;
    }

    public void setPlannerCompleted(boolean plannerCompleted) {
        if (this.plannerCompleted != plannerCompleted) {
            MyMod.LOG.debug("[ChainRuntime] Player {} plannerCompleted {} -> {} traversalTargets={} pendingBreakTargets={}",
                playerUUID, this.plannerCompleted, plannerCompleted, traversalTargets.size(), pendingBreakTargets.size());
        }
        this.plannerCompleted = plannerCompleted;
    }

    public long getPlannerHeartbeatMillis() {
        return plannerHeartbeatMillis;
    }

    public void updatePlannerHeartbeat() {
        this.plannerHeartbeatMillis = System.currentTimeMillis();
    }

    public int getMatchedTargetCount() {
        return matchedTargetCount;
    }

    public void setMatchedTargetCount(int matchedTargetCount) {
        this.matchedTargetCount = Math.max(0, matchedTargetCount);
    }

    public void resetExecutorThrottle() {
        nextExecutorAllowedMillis.set(0L);
    }

    public boolean isExecutorReady(long nowMillis) {
        return nowMillis >= nextExecutorAllowedMillis.get();
    }

    public void scheduleNextExecutorRun(long nowMillis, long intervalMillis) {
        nextExecutorAllowedMillis.set(nowMillis + Math.max(0L, intervalMillis));
    }

    public void clear(String reason) {
        int queuedTargets = pendingBreakTargets.size();
        if (plannerSubscription != null) {
            plannerSubscription.unregister();
            setPlannerSubscription(null);
        }
        if (executorSubscription != null) {
            executorSubscription.unregister();
            setExecutorSubscription(null);
        }
        traversalTargets.clear();
        pendingBreakTargets.clear();
        plannerRunning = false;
        plannerCompleted = false;
        plannerHeartbeatMillis = 0L;
        matchedTargetCount = 0;
        resetExecutorThrottle();
        MyMod.LOG.debug("[ChainRuntime] Cleared runtime state for player {}, reason={}, queuedTargets={}",
            playerUUID, reason, queuedTargets);
    }
}
