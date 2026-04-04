package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;

/**
 * 单次连锁任务会话。
 */
public class ChainSession {

    private final UUID playerUUID;
    private final ChainMode mode;
    private final ChainTarget origin;
    private volatile ParallelTickSubscription plannerSubscription;
    private volatile ParallelTickSubscription executorSubscription;
    private final ConcurrentLinkedQueue<ChainTarget> traversalTargets = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChainTarget> pendingBreakTargets = new ConcurrentLinkedQueue<>();
    private volatile boolean plannerRunning;
    private volatile boolean plannerCompleted;
    private volatile long plannerHeartbeatMillis;
    private final AtomicLong nextExecutorAllowedMillis = new AtomicLong();

    public ChainSession(UUID playerUUID, ChainMode mode, ChainTarget origin) {
        this.playerUUID = playerUUID;
        this.mode = mode;
        this.origin = origin;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public ChainMode getMode() {
        return mode;
    }

    public ChainTarget getOrigin() {
        return origin;
    }

    public ParallelTickSubscription getPlannerSubscription() {
        return plannerSubscription;
    }

    public void setPlannerSubscription(ParallelTickSubscription plannerSubscription) {
        if (this.plannerSubscription == null && plannerSubscription != null) {
            MyMod.LOG.debug("[ChainSession] Player {} plannerSubscription attached", playerUUID);
        } else if (this.plannerSubscription != null && plannerSubscription == null) {
            MyMod.LOG.debug("[ChainSession] Player {} plannerSubscription cleared queuedTargets={}",
                playerUUID, pendingBreakTargets.size());
        }
        this.plannerSubscription = plannerSubscription;
    }

    public ParallelTickSubscription getExecutorSubscription() {
        return executorSubscription;
    }

    public void setExecutorSubscription(ParallelTickSubscription executorSubscription) {
        if (this.executorSubscription == null && executorSubscription != null) {
            MyMod.LOG.debug("[ChainSession] Player {} executorSubscription attached", playerUUID);
        } else if (this.executorSubscription != null && executorSubscription == null) {
            MyMod.LOG.debug("[ChainSession] Player {} executorSubscription cleared queuedTargets={}",
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
            MyMod.LOG.debug("[ChainSession] Player {} plannerRunning {} -> {} traversalTargets={} pendingBreakTargets={}",
                playerUUID, this.plannerRunning, plannerRunning, traversalTargets.size(), pendingBreakTargets.size());
        }
        this.plannerRunning = plannerRunning;
    }

    public boolean isPlannerCompleted() {
        return plannerCompleted;
    }

    public void setPlannerCompleted(boolean plannerCompleted) {
        if (this.plannerCompleted != plannerCompleted) {
            MyMod.LOG.debug("[ChainSession] Player {} plannerCompleted {} -> {} traversalTargets={} pendingBreakTargets={}",
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

    public void resetExecutorThrottle() {
        nextExecutorAllowedMillis.set(0L);
    }

    public boolean isExecutorReady(long nowMillis) {
        return nowMillis >= nextExecutorAllowedMillis.get();
    }

    public void scheduleNextExecutorRun(long nowMillis, long intervalMillis) {
        nextExecutorAllowedMillis.set(nowMillis + Math.max(0L, intervalMillis));
    }

    public void clearRuntimeState(String reason) {
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
        resetExecutorThrottle();
        MyMod.LOG.debug("[ChainSession] Cleared runtime state for player {}, reason={}, queuedTargets={}",
            playerUUID, reason, queuedTargets);
    }
}
