package club.heiqi.qz_miner.chain.state;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

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
    private volatile ChainSubMode selectedSubMode = ChainModeRegistry.getDefaultSubMode(ChainModeRegistry.getDefaultMode());
    private volatile int serverChainRadius = Config.chainRadius;
    private volatile int serverChainMaxBlocks = Config.chainMaxBlocks;
    private volatile int serverMatchedTargetCount;

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
        this.selectedSubMode = ChainModeRegistry.resolveSubMode(this.selectedMode, selectedSubMode);
    }

    public ChainSubMode getSelectedSubMode() {
        return selectedSubMode;
    }

    public void setSelectedSubMode(ChainSubMode selectedSubMode) {
        this.selectedSubMode = ChainModeRegistry.resolveSubMode(selectedMode, selectedSubMode);
    }

    public int getServerChainRadius() {
        return serverChainRadius;
    }

    public void setServerChainRadius(int serverChainRadius) {
        this.serverChainRadius = Math.max(1, serverChainRadius);
    }

    public int getServerChainMaxBlocks() {
        return serverChainMaxBlocks;
    }

    public void setServerChainMaxBlocks(int serverChainMaxBlocks) {
        this.serverChainMaxBlocks = Math.max(1, serverChainMaxBlocks);
    }

    public int getServerMatchedTargetCount() {
        return serverMatchedTargetCount;
    }

    public void setServerMatchedTargetCount(int serverMatchedTargetCount) {
        this.serverMatchedTargetCount = Math.max(0, serverMatchedTargetCount);
    }

    public boolean isChainActiveDisplay() {
        return serverChainKeyPressed || serverExecuting || chainKeyPressed;
    }
}
