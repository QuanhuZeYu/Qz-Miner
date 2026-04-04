package club.heiqi.qz_miner.chain.state;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import net.minecraft.item.ItemStack;

/**
 * 服务端玩家连锁状态。
 */
public class ChainPlayerState {

    private final UUID playerUUID;
    private volatile boolean chainKeyPressed;
    private volatile ChainExecutionStatus executionStatus = ChainExecutionStatus.IDLE;
    private ChainMode selectedMode = ChainModeRegistry.getDefaultMode();
    private volatile ChainSession session;
    private final List<ItemStack> pendingDrops = new ArrayList<ItemStack>();

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
            int queuedTargets = session == null ? 0 : session.getPendingBreakTargets().size();
            int pendingDropsCount = pendingDrops.size();
            boolean waitingForPlanner = session != null && session.isPlannerRunning();
            MyMod.LOG.debug(
                "[ChainState] Player {} executionStatus {} -> {} reason={} queuedTargets={} pendingDrops={} waitingForPlanner={}",
                playerUUID,
                this.executionStatus,
                newStatus,
                reason,
                queuedTargets,
                pendingDropsCount,
                waitingForPlanner);
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

    public ChainSession getSession() {
        return session;
    }

    public void setSession(ChainSession session) {
        if (this.session == null && session != null) {
            MyMod.LOG.debug("[ChainState] Player {} session attached mode={} origin=({}, {}, {})",
                playerUUID,
                session.getMode(),
                session.getOrigin().getX(),
                session.getOrigin().getY(),
                session.getOrigin().getZ());
        } else if (this.session != null && session == null) {
            MyMod.LOG.debug("[ChainState] Player {} session cleared", playerUUID);
        }
        this.session = session;
    }

    public void clearSession() {
        setSession(null);
    }

    public List<ItemStack> getPendingDrops() {
        return pendingDrops;
    }

    public void clearRuntimeState() {
        clearRuntimeState("unspecified");
    }

    public void clearRuntimeState(String reason) {
        setExecutionStatus(ChainExecutionStatus.IDLE, reason);
        if (session != null) {
            session.clearRuntimeState(reason);
            clearSession();
        }
        MyMod.LOG.debug("[ChainState] Cleared runtime state for player {}, reason={}", playerUUID, reason);
    }
}
