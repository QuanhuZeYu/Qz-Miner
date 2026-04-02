package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 连锁模式注册表。
 *
 * 当前阶段先提供稳定的模式列表与循环切换能力，
 * 后续再扩展到模式与规划/执行策略的映射。
 */
public final class ChainModeRegistry {

    private static final List<ChainMode> REGISTERED_MODES = Collections.unmodifiableList(Arrays.asList(
        ChainMode.CHAIN,
        ChainMode.AREA,
        ChainMode.INTERACT,
        ChainMode.SPECIAL));

    private ChainModeRegistry() {}

    public static List<ChainMode> getRegisteredModes() {
        return REGISTERED_MODES;
    }

    public static ChainMode getDefaultMode() {
        return ChainMode.CHAIN;
    }

    public static ChainMode next(ChainMode currentMode) {
        int index = REGISTERED_MODES.indexOf(currentMode);
        if (index < 0) {
            return getDefaultMode();
        }
        return REGISTERED_MODES.get((index + 1) % REGISTERED_MODES.size());
    }

    public static ChainMode previous(ChainMode currentMode) {
        int index = REGISTERED_MODES.indexOf(currentMode);
        if (index < 0) {
            return getDefaultMode();
        }
        return REGISTERED_MODES.get((index - 1 + REGISTERED_MODES.size()) % REGISTERED_MODES.size());
    }
}
