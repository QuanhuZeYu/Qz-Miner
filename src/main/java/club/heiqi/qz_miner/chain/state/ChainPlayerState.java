package club.heiqi.qz_miner.chain.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import net.minecraft.item.ItemStack;

/**
 * 服务端玩家连锁状态。
 */
public class ChainPlayerState {

    private final UUID playerUUID;
    private volatile boolean chainKeyPressed;
    private volatile ChainExecutionStatus executionStatus = ChainExecutionStatus.IDLE;
    private ChainMode selectedMode = ChainModeRegistry.getDefaultMode();
    private volatile ParallelTickSubscription plannerSubscription;
    private volatile ParallelTickSubscription executorSubscription;
    private final ConcurrentLinkedQueue<ChainTarget> traversalTargets = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChainTarget> pendingBreakTargets = new ConcurrentLinkedQueue<>();
    private final List<ItemStack> pendingDrops = new ArrayList<>();
    private volatile boolean plannerRunning;
    private volatile boolean plannerCompleted;
    private volatile long plannerHeartbeatMillis;
    private final AtomicLong nextExecutorAllowedMillis = new AtomicLong();

    public ChainPlayerState(UUID playerUUID) {
        this.playerUUID = playerUUID;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    public boolean isChainKeyPressed() {
        return chainKeyPressed;
    }

    public void setChainKeyPressed(boolean chainKeyPressed) {
        if (this.chainKeyPressed != chainKeyPressed) {
            MyMod.LOG.debug("[ChainState] Player {} chainKeyPressed {} -> {}", playerUUID, this.chainKeyPressed, chainKeyPressed);
        }
        this.chainKeyPressed = chainKeyPressed;
    }

    public boolean isExecuting() {
        return executionStatus != ChainExecutionStatus.IDLE;
    }

    public void setExecuting(boolean executing) {
        this.executionStatus = executing ? ChainExecutionStatus.RUNNING : ChainExecutionStatus.IDLE;
    }

    public ChainExecutionStatus getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(ChainExecutionStatus executionStatus) {
        setExecutionStatus(executionStatus, "unspecified");
    }

    public void setExecutionStatus(ChainExecutionStatus executionStatus, String reason) {
        ChainExecutionStatus newStatus = executionStatus == null ? ChainExecutionStatus.IDLE : executionStatus;
        if (this.executionStatus != newStatus) {
            MyMod.LOG.debug(
                "[ChainState] Player {} executionStatus {} -> {} reason={} queuedTargets={} pendingDrops={} waitingForPlanner={}",
                playerUUID,
                this.executionStatus,
                newStatus,
                reason,
                pendingBreakTargets.size(),
                pendingDrops.size(),
                plannerRunning);
        }
        this.executionStatus = newStatus;
    }

    public ChainMode getSelectedMode() {
        return selectedMode;
    }

    public void setSelectedMode(ChainMode selectedMode) {
        ChainMode newMode = selectedMode == null ? ChainModeRegistry.getDefaultMode() : selectedMode;
        if (this.selectedMode != newMode) {
            MyMod.LOG.debug("[ChainState] Player {} selectedMode {} -> {}", playerUUID, this.selectedMode, newMode);
        }
        this.selectedMode = newMode;
    }

    public ParallelTickSubscription getPlannerSubscription() {
        return plannerSubscription;
    }

    public void setPlannerSubscription(ParallelTickSubscription plannerSubscription) {
        if (this.plannerSubscription == null && plannerSubscription != null) {
            MyMod.LOG.debug("[ChainState] Player {} plannerSubscription attached", playerUUID);
        } else if (this.plannerSubscription != null && plannerSubscription == null) {
            MyMod.LOG.debug("[ChainState] Player {} plannerSubscription cleared queuedTargets={} pendingDrops={}",
                playerUUID, pendingBreakTargets.size(), pendingDrops.size());
        }
        this.plannerSubscription = plannerSubscription;
    }

    public ParallelTickSubscription getExecutorSubscription() {
        return executorSubscription;
    }

    public void setExecutorSubscription(ParallelTickSubscription executorSubscription) {
        if (this.executorSubscription == null && executorSubscription != null) {
            MyMod.LOG.debug("[ChainState] Player {} executorSubscription attached", playerUUID);
        } else if (this.executorSubscription != null && executorSubscription == null) {
            MyMod.LOG.debug("[ChainState] Player {} executorSubscription cleared queuedTargets={} pendingDrops={}",
                playerUUID, pendingBreakTargets.size(), pendingDrops.size());
        }
        this.executorSubscription = executorSubscription;
    }

    public ConcurrentLinkedQueue<ChainTarget> getTraversalTargets() {
        return traversalTargets;
    }

    public ConcurrentLinkedQueue<ChainTarget> getPendingBreakTargets() {
        return pendingBreakTargets;
    }

    public List<ItemStack> getPendingDrops() {
        return pendingDrops;
    }

    public boolean isPlannerRunning() {
        return plannerRunning;
    }

    public void setPlannerRunning(boolean plannerRunning) {
        if (this.plannerRunning != plannerRunning) {
            MyMod.LOG.debug("[ChainState] Player {} plannerRunning {} -> {} traversalTargets={} pendingBreakTargets={}",
                playerUUID, this.plannerRunning, plannerRunning, traversalTargets.size(), pendingBreakTargets.size());
        }
        this.plannerRunning = plannerRunning;
    }

    public boolean isPlannerCompleted() {
        return plannerCompleted;
    }

    public void setPlannerCompleted(boolean plannerCompleted) {
        if (this.plannerCompleted != plannerCompleted) {
            MyMod.LOG.debug("[ChainState] Player {} plannerCompleted {} -> {} traversalTargets={} pendingBreakTargets={}",
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

    public long getNextExecutorAllowedMillis() {
        return nextExecutorAllowedMillis.get();
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

    public void clearRuntimeState() {
        clearRuntimeState("unspecified");
    }

    public void clearRuntimeState(String reason) {
        int queuedTargets = pendingBreakTargets.size();
        int pendingDropStacks = pendingDrops.size();
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
        setExecutionStatus(ChainExecutionStatus.IDLE, reason);
        setPlannerRunning(false);
        setPlannerCompleted(false);
        plannerHeartbeatMillis = 0L;
        resetExecutorThrottle();
        MyMod.LOG.debug("[ChainState] Cleared runtime state for player {}, reason={}, queuedTargets={}, pendingDrops={}",
            playerUUID, reason, queuedTargets, pendingDropStacks);
    }
}
