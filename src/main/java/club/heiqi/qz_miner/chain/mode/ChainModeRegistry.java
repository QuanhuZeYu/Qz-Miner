package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    private static final Map<ChainMode, ChainModeDefinition> MODE_DEFINITIONS = new EnumMap<ChainMode, ChainModeDefinition>(ChainMode.class);

    private ChainModeRegistry() {}

    public static void clearDefinitions() {
        MODE_DEFINITIONS.clear();
    }

    public static void register(ChainModeDefinition definition) {
        if (definition == null || definition.getMode() == null) {
            return;
        }
        MODE_DEFINITIONS.put(definition.getMode(), definition);
    }

    public static List<ChainMode> getRegisteredModes() {
        return REGISTERED_MODES;
    }

    public static ChainMode getDefaultMode() {
        return ChainMode.CHAIN;
    }

    public static ChainModeDefinition getDefinition(ChainMode mode) {
        return MODE_DEFINITIONS.get(mode);
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
