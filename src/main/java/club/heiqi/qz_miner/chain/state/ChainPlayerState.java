package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.GregTechCableSessionState;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * 服务端玩家连锁状态。
 */
public class ChainPlayerState extends AbstractChainModeState {

    private final UUID playerUUID;
    private final ChainPlayerDropBuffer dropBuffer = new ChainPlayerDropBuffer();
    private volatile boolean chainKeyPressed;
    private volatile ChainExecutionStatus executionStatus = ChainExecutionStatus.IDLE;
    private volatile int requestedChainRadius = -1;
    private volatile int requestedChainMaxBlocks = -1;
    private volatile ChainSession session;

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
        ChainSession currentSession = this.session;
        if (this.executionStatus != newStatus) {
            int queuedTargets = currentSession == null ? 0 : currentSession.getPendingBreakTargets().size();
            int pendingDropsCount = dropBuffer.size();
            boolean waitingForPlanner = currentSession != null && currentSession.isPlannerRunning();
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
        return super.getSelectedMode();
    }

    public void setSelectedMode(ChainMode selectedMode) {
        ChainMode previousMode = getSelectedMode();
        ChainMode newMode = club.heiqi.qz_miner.chain.mode.ChainModeRegistry.resolveMode(selectedMode);
        if (previousMode != newMode) {
            MyMod.LOG.debug("[ChainState] Player {} selectedMode {} -> {}", playerUUID, previousMode, newMode);
        }
        setSelectedModeInternal(newMode);
    }

    /**
     * 获取当前主模式下的子模式。
     *
     * @return 当前子模式
     */
    public ChainSubMode getSelectedSubMode() {
        return super.getSelectedSubMode();
    }

    /**
     * 设置当前主模式下的子模式。
     *
     * @param selectedSubMode 当前子模式
     */
    public void setSelectedSubMode(ChainSubMode selectedSubMode) {
        ChainSubMode previousSubMode = getSelectedSubMode();
        ChainSubMode newSubMode = club.heiqi.qz_miner.chain.mode.ChainModeRegistry.resolveSubMode(getSelectedMode(), selectedSubMode);
        if (previousSubMode != newSubMode) {
            MyMod.LOG.debug("[ChainState] Player {} selectedSubMode {} -> {}", playerUUID, previousSubMode, newSubMode);
        }
        setSelectedSubModeInternal(selectedSubMode);
    }

    public ChainSession getSession() {
        return session;
    }

    int getMatchedTargetCount() {
        ChainSession currentSession = this.session;
        return currentSession == null ? 0 : currentSession.getMatchedTargetCount();
    }

    int getPendingBreakTargetCount() {
        ChainSession currentSession = this.session;
        return currentSession == null ? 0 : currentSession.getPendingBreakTargets().size();
    }

    boolean hasPlannerSubscription() {
        ChainSession currentSession = this.session;
        return currentSession != null && currentSession.hasPlannerSubscription();
    }

    public boolean isSessionActive(ChainSession session) {
        return session != null && this.session == session;
    }

    /**
     * 获取玩家级掉落缓冲。
     *
     * @return 掉落缓冲
     */
    public ChainPlayerDropBuffer getDropBuffer() {
        return dropBuffer;
    }

    public int getRequestedChainRadius() {
        return requestedChainRadius;
    }

    public void setRequestedChainRadius(int requestedChainRadius) {
        this.requestedChainRadius = requestedChainRadius;
    }

    public int getRequestedChainMaxBlocks() {
        return requestedChainMaxBlocks;
    }

    public void setRequestedChainMaxBlocks(int requestedChainMaxBlocks) {
        this.requestedChainMaxBlocks = requestedChainMaxBlocks;
    }

    public void setSession(ChainSession session) {
        ChainSession previousSession = this.session;
        if (previousSession != null && previousSession != session) {
            GregTechCableSessionState.clear(previousSession);
        }
        if (this.session == null && session != null) {
            MyMod.LOG.debug("[ChainState] Player {} session attached mode={} origin=({}, {}, {})",
                playerUUID,
                session.getRequest().getMode(),
                session.getRequest().getOrigin().getX(),
                session.getRequest().getOrigin().getY(),
                session.getRequest().getOrigin().getZ());
        } else if (this.session != null && session == null) {
            MyMod.LOG.debug("[ChainState] Player {} session cleared", playerUUID);
        }
        this.session = session;
    }

    public void clearSession() {
        setSession(null);
    }

    public void clearRuntimeState() {
        clearRuntimeState("unspecified");
    }

    public void clearRuntimeState(String reason) {
        setExecutionStatus(ChainExecutionStatus.IDLE, reason);
        ChainSession currentSession = this.session;
        if (currentSession != null) {
            GregTechCableSessionState.clear(currentSession);
            currentSession.clearRuntimeState(reason);
            if (this.session == currentSession) {
                clearSession();
            }
        }
        MyMod.LOG.debug("[ChainState] Cleared runtime state for player {}, reason={}", playerUUID, reason);
    }

    /**
     * 停止当前连锁执行。
     *
     * 玩家级掉落缓冲会由独立释放流程处理，因此此处只负责结束会话运行态。
     *
     * @param reason 停止原因
     */
    public void stopExecutionPreservingDrops(String reason) {
        setExecutionStatus(ChainExecutionStatus.IDLE, reason);
        ChainSession currentSession = this.session;
        if (currentSession == null) {
            return;
        }

        GregTechCableSessionState.clear(currentSession);
        currentSession.stopExecutionPreservingDrops(reason);
        if (this.session == currentSession) {
            clearSession();
        }
        MyMod.LOG.debug("[ChainState] Stopped execution for player {}, reason={}, pendingDrops={}",
            playerUUID, reason, dropBuffer.size());
    }
}
