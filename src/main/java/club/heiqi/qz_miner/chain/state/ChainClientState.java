package club.heiqi.qz_miner.chain.state;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;

/**
 * 客户端连锁状态。
 */
public class ChainClientState {

    private boolean chainKeyPressed;
    private boolean previewActive;
    private ChainMode selectedMode = ChainModeRegistry.getDefaultMode();

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

    public ChainMode getSelectedMode() {
        return selectedMode;
    }

    public void setSelectedMode(ChainMode selectedMode) {
        this.selectedMode = selectedMode == null ? ChainModeRegistry.getDefaultMode() : selectedMode;
    }
}
