package club.heiqi.qz_miner.chain.state;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;

/**
 * 客户端连锁状态。
 */
public class ChainClientState {

    private volatile boolean chainKeyPressed;
    private volatile boolean previewActive;
    private volatile boolean serverChainKeyPressed;
    private volatile boolean serverExecuting;
    private volatile ChainExecutionStatus serverExecutionStatus = ChainExecutionStatus.IDLE;
    private volatile ChainMode selectedMode = ChainModeRegistry.getDefaultMode();

    public boolean isChainKeyPressed() {
        return chainKeyPressed;
    }

    public void setChainKeyPressed(boolean chainKeyPressed) {
        this.chainKeyPressed = chainKeyPressed;
    }

    public boolean isPreviewActive() {
        return previewActive;
    }

    public void setPreviewActive(boolean previewActive) {
        this.previewActive = previewActive;
    }

    public boolean isServerChainKeyPressed() {
        return serverChainKeyPressed;
    }

    public void setServerChainKeyPressed(boolean serverChainKeyPressed) {
        this.serverChainKeyPressed = serverChainKeyPressed;
    }

    public boolean isServerExecuting() {
        return serverExecuting;
    }

    public void setServerExecuting(boolean serverExecuting) {
        this.serverExecuting = serverExecuting;
    }

    public ChainExecutionStatus getServerExecutionStatus() {
        return serverExecutionStatus;
    }

    public void setServerExecutionStatus(ChainExecutionStatus serverExecutionStatus) {
        this.serverExecutionStatus = serverExecutionStatus == null ? ChainExecutionStatus.IDLE : serverExecutionStatus;
    }

    public ChainMode getSelectedMode() {
        return selectedMode;
    }

    public void setSelectedMode(ChainMode selectedMode) {
        this.selectedMode = selectedMode == null ? ChainModeRegistry.getDefaultMode() : selectedMode;
    }

    public boolean isChainActiveDisplay() {
        return serverChainKeyPressed || serverExecuting || chainKeyPressed;
    }
}
