package club.heiqi.qz_miner.chain.state;

import java.util.UUID;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;

/**
 * 服务端玩家连锁状态。
 */
public class ChainPlayerState {

    private final UUID playerUUID;
    private boolean chainKeyPressed;
    private boolean executing;
    private ChainMode selectedMode = ChainModeRegistry.getDefaultMode();

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
        return executing;
    }

    public void setExecuting(boolean executing) {
        this.executing = executing;
    }

    public ChainMode getSelectedMode() {
        return selectedMode;
    }

    public void setSelectedMode(ChainMode selectedMode) {
        this.selectedMode = selectedMode == null ? ChainModeRegistry.getDefaultMode() : selectedMode;
    }
}
