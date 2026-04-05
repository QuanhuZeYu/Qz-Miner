package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import club.heiqi.qz_miner.MyMod;

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
            MyMod.LOG.warn("[ChainModeRegistry] Ignore invalid mode definition: definition={}, mode=null", definition);
            return;
        }
        if (!REGISTERED_MODES.contains(definition.getMode())) {
            MyMod.LOG.warn("[ChainModeRegistry] Ignore unregistered mode definition: mode={}", definition.getMode());
            return;
        }
        if (definition.getPlanningStrategy() == null) {
            MyMod.LOG.warn("[ChainModeRegistry] Mode {} missing planning strategy", definition.getMode());
        }
        if (definition.getActionExecutor() == null) {
            MyMod.LOG.warn("[ChainModeRegistry] Mode {} missing action executor", definition.getMode());
        }
        if (definition.createTraverser(null) == null) {
            MyMod.LOG.warn("[ChainModeRegistry] Mode {} missing traverser", definition.getMode());
        }
        if (definition.getSubModes().isEmpty()) {
            MyMod.LOG.warn("[ChainModeRegistry] Mode {} has no sub modes", definition.getMode());
        }
        if (MODE_DEFINITIONS.containsKey(definition.getMode())) {
            MyMod.LOG.warn("[ChainModeRegistry] Duplicate mode definition detected, overriding mode={}", definition.getMode());
        }
        MODE_DEFINITIONS.put(definition.getMode(), definition);
        MyMod.LOG.debug("[ChainModeRegistry] Registered mode definition: mode={}, subModes={}, defaultSubMode={}",
            definition.getMode(), definition.getSubModes().size(), definition.getDefaultSubMode());
    }

    public static List<ChainMode> getRegisteredModes() {
        return REGISTERED_MODES;
    }

    public static ChainMode getDefaultMode() {
        return ChainMode.CHAIN;
    }

    public static ChainModeDefinition getDefinition(ChainMode mode) {
        ChainModeDefinition definition = MODE_DEFINITIONS.get(mode);
        if (definition == null && mode != null) {
            MyMod.LOG.warn("[ChainModeRegistry] Missing mode definition: mode={}", mode);
        }
        return definition;
    }

    /**
     * 获取指定主模式的默认子模式。
     *
     * @param mode 主模式
     * @return 默认子模式
     */
    public static ChainSubMode getDefaultSubMode(ChainMode mode) {
        ChainModeDefinition definition = getDefinition(mode);
        return definition == null ? null : definition.getDefaultSubMode();
    }

    /**
     * 规范化指定主模式下的子模式。
     *
     * @param mode 主模式
     * @param subMode 子模式
     * @return 规范化后的子模式
     */
    public static ChainSubMode resolveSubMode(ChainMode mode, ChainSubMode subMode) {
        ChainModeDefinition definition = getDefinition(mode);
        return definition == null ? null : definition.resolveSubMode(subMode);
    }

    /**
     * 获取指定主模式下的下一个子模式。
     *
     * @param mode 主模式
     * @param currentSubMode 当前子模式
     * @return 下一个子模式
     */
    public static ChainSubMode nextSubMode(ChainMode mode, ChainSubMode currentSubMode) {
        ChainModeDefinition definition = getDefinition(mode);
        return definition == null ? null : definition.nextSubMode(currentSubMode);
    }

    /**
     * 获取指定主模式下的上一个子模式。
     *
     * @param mode 主模式
     * @param currentSubMode 当前子模式
     * @return 上一个子模式
     */
    public static ChainSubMode previousSubMode(ChainMode mode, ChainSubMode currentSubMode) {
        ChainModeDefinition definition = getDefinition(mode);
        return definition == null ? null : definition.previousSubMode(currentSubMode);
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

    /**
     * 校验当前注册结果，补充缺失定义日志。
     */
    public static void validateDefinitions() {
        for (ChainMode mode : REGISTERED_MODES) {
            ChainModeDefinition definition = MODE_DEFINITIONS.get(mode);
            if (definition == null) {
                MyMod.LOG.warn("[ChainModeRegistry] Missing required mode definition after bootstrap: mode={}", mode);
                continue;
            }
            if (definition.getDefaultSubMode() == null) {
                MyMod.LOG.warn("[ChainModeRegistry] Mode {} missing default sub mode", mode);
            }
        }
    }
}
