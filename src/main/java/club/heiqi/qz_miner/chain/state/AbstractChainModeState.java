package club.heiqi.qz_miner.chain.state;

import java.util.EnumMap;
import java.util.Map;

import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;

/**
 * 连锁模式选择公共状态。
 */
public abstract class AbstractChainModeState {

    private ChainMode selectedMode = ChainModeRegistry.getDefaultMode();
    private ChainSubMode selectedSubMode = ChainModeRegistry.getDefaultSubMode(ChainModeRegistry.getDefaultMode());
    private final Map<ChainMode, ChainSubMode> rememberedSubModes = new EnumMap<ChainMode, ChainSubMode>(ChainMode.class);

    protected AbstractChainModeState() {
        rememberCurrentSubMode(selectedMode, selectedSubMode);
    }

    public ChainMode getSelectedMode() {
        return selectedMode;
    }

    public ChainSubMode getSelectedSubMode() {
        return selectedSubMode;
    }

    protected final ChainMode setSelectedModeInternal(ChainMode selectedMode) {
        ChainMode previousMode = this.selectedMode;
        ChainMode newMode = selectedMode == null ? ChainModeRegistry.getDefaultMode() : selectedMode;
        this.selectedMode = newMode;
        ChainSubMode rememberedSubMode = rememberedSubModes.get(newMode);
        this.selectedSubMode = ChainModeRegistry.resolveSubMode(newMode, rememberedSubMode);
        rememberCurrentSubMode(this.selectedMode, this.selectedSubMode);
        return previousMode;
    }

    protected final ChainSubMode setSelectedSubModeInternal(ChainSubMode selectedSubMode) {
        ChainSubMode previousSubMode = this.selectedSubMode;
        this.selectedSubMode = ChainModeRegistry.resolveSubMode(this.selectedMode, selectedSubMode);
        rememberCurrentSubMode(this.selectedMode, this.selectedSubMode);
        return previousSubMode;
    }

    private void rememberCurrentSubMode(ChainMode mode, ChainSubMode subMode) {
        if (mode == null || subMode == null) {
            return;
        }

        rememberedSubModes.put(mode, subMode);
    }
}
