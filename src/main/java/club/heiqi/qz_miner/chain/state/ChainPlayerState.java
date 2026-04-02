package club.heiqi.qz_miner.chain.state;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    private boolean chainKeyPressed;
    private ChainExecutionStatus executionStatus = ChainExecutionStatus.IDLE;
    private ChainMode selectedMode = ChainModeRegistry.getDefaultMode();
    private ParallelTickSubscription plannerSubscription;
    private final ConcurrentLinkedQueue<ChainTarget> plannedTargets = new ConcurrentLinkedQueue<>();
    private final List<ItemStack> pendingDrops = new ArrayList<>();

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
        this.executionStatus = executionStatus == null ? ChainExecutionStatus.IDLE : executionStatus;
    }

    public ChainMode getSelectedMode() {
        return selectedMode;
    }

    public void setSelectedMode(ChainMode selectedMode) {
        this.selectedMode = selectedMode == null ? ChainModeRegistry.getDefaultMode() : selectedMode;
    }

    public ParallelTickSubscription getPlannerSubscription() {
        return plannerSubscription;
    }

    public void setPlannerSubscription(ParallelTickSubscription plannerSubscription) {
        this.plannerSubscription = plannerSubscription;
    }

    public ConcurrentLinkedQueue<ChainTarget> getPlannedTargets() {
        return plannedTargets;
    }

    public List<ItemStack> getPendingDrops() {
        return pendingDrops;
    }

    public void clearRuntimeState() {
        if (plannerSubscription != null) {
            plannerSubscription.unregister();
            plannerSubscription = null;
        }
        plannedTargets.clear();
        executionStatus = ChainExecutionStatus.IDLE;
    }
}
