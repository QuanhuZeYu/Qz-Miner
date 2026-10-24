package club.heiqi.qz_miner.client.hud;

import java.util.function.Function;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;

/** 模式身份、可用邻项与本次输入方向；只在选择或文案资料变化时建立。 */
public final class MinerHudSelection {
    public final ChainMode mode, previousMode, nextMode;
    public final ChainSubMode subMode;
    public final String title, subtitle, left, right, above, below;
    public final int horizontal, vertical;

    MinerHudSelection(ChainMode mode, ChainSubMode subMode, MinerHudSelection previous,
            Function<String, String> text, boolean registry) {
        this(mode, subMode, previous, text, registry, 0);
    }
    MinerHudSelection(ChainMode mode, ChainSubMode subMode, MinerHudSelection previous,
            Function<String, String> text, boolean registry, int direction) {
        this.mode = mode; this.subMode = subMode;
        previousMode = registry ? ChainModeRegistry.previous(mode) : null;
        nextMode = registry ? ChainModeRegistry.next(mode) : null;
        ChainSubMode before = registry ? ChainModeRegistry.previousSubMode(mode, subMode) : null;
        ChainSubMode after = registry ? ChainModeRegistry.nextSubMode(mode, subMode) : null;
        title = text.apply(mode.getDisplayNameKey());
        subtitle = subMode == null ? "" : text.apply(subMode.getDisplayNameKey());
        left = neighbor(previousMode, text); right = neighbor(nextMode, text);
        above = neighbor(before, text); below = neighbor(after, text);
        horizontal = mainDirection(previous, direction);
        vertical = subDirection(previous, registry, direction);
    }
    private String neighbor(ChainMode other, Function<String, String> text) {
        return other == null || other == mode ? "" : text.apply(other.getDisplayNameKey());
    }
    private String neighbor(ChainSubMode other, Function<String, String> text) {
        return other == null || other == subMode ? "" : text.apply(other.getDisplayNameKey());
    }
    private int mainDirection(MinerHudSelection previous, int direction) {
        if (previous == null || previous.mode == mode) return 0;
        return direction != 0 ? direction : previous.nextMode == mode ? 1 : -1;
    }
    private int subDirection(MinerHudSelection previous, boolean registry, int direction) {
        if (previous == null || previous.mode != mode || previous.subMode == subMode) return 0;
        if (direction != 0) return direction;
        return registry && ChainModeRegistry.nextSubMode(mode, previous.subMode) == subMode ? 1 : -1;
    }
}
