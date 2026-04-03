package club.heiqi.qz_miner.chain.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    private final ConcurrentLinkedQueue<ChainTarget> plannedTargets = new ConcurrentLinkedQueue<>();
    private final List<ItemStack> pendingDrops = new ArrayList<>();
    private volatile boolean executorWaitingForPlanner;
    private volatile long plannerHeartbeatMillis;
    private volatile long executorHeartbeatTick;

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
        return executionStatus == ChainExecutionStatus.EXECUTING || executionStatus == ChainExecutionStatus.PLANNING;
    }

    public void setExecuting(boolean executing) {
        this.executionStatus = executing ? ChainExecutionStatus.EXECUTING : ChainExecutionStatus.IDLE;
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
                plannedTargets.size(),
                pendingDrops.size(),
                executorWaitingForPlanner);
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
                playerUUID, plannedTargets.size(), pendingDrops.size());
        }
        this.plannerSubscription = plannerSubscription;
    }

    public ConcurrentLinkedQueue<ChainTarget> getPlannedTargets() {
        return plannedTargets;
    }

    public List<ItemStack> getPendingDrops() {
        return pendingDrops;
    }

    public boolean isExecutorWaitingForPlanner() {
        return executorWaitingForPlanner;
    }

    public void setExecutorWaitingForPlanner(boolean executorWaitingForPlanner) {
        if (this.executorWaitingForPlanner != executorWaitingForPlanner) {
            MyMod.LOG.debug("[ChainState] Player {} executorWaitingForPlanner {} -> {} queuedTargets={} pendingDrops={}",
                playerUUID, this.executorWaitingForPlanner, executorWaitingForPlanner, plannedTargets.size(), pendingDrops.size());
        }
        this.executorWaitingForPlanner = executorWaitingForPlanner;
    }

    public long getPlannerHeartbeatMillis() {
        return plannerHeartbeatMillis;
    }

    public void updatePlannerHeartbeat() {
        this.plannerHeartbeatMillis = System.currentTimeMillis();
    }

    public long getExecutorHeartbeatTick() {
        return executorHeartbeatTick;
    }

    public void updateExecutorHeartbeat(long heartbeatTick) {
        this.executorHeartbeatTick = heartbeatTick;
    }

    public void clearRuntimeState() {
        clearRuntimeState("unspecified");
    }

    public void clearRuntimeState(String reason) {
        int queuedTargets = plannedTargets.size();
        int pendingDropStacks = pendingDrops.size();
        if (plannerSubscription != null) {
            plannerSubscription.unregister();
            setPlannerSubscription(null);
        }
        plannedTargets.clear();
        setExecutionStatus(ChainExecutionStatus.IDLE, reason);
        setExecutorWaitingForPlanner(false);
        plannerHeartbeatMillis = 0L;
        executorHeartbeatTick = 0L;
        MyMod.LOG.debug("[ChainState] Cleared runtime state for player {}, reason={}, queuedTargets={}, pendingDrops={}",
            playerUUID, reason, queuedTargets, pendingDropStacks);
    }
}
