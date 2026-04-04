package club.heiqi.qz_miner.chain.mode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.ChainPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTraverser;

/**
 * 连锁模式定义。
 */
public final class ChainModeDefinition {

    private final ChainMode mode;
    private final ChainPlanningStrategy planningStrategy;
    private final ChainActionExecutor actionExecutor;
    private final List<ChainSubMode> subModes;
    private final ChainSubMode defaultSubMode;
    private final ChainTraverser traverser;
    private final ChainBlockMatcherResolver matcherResolver;
    private final boolean showAreaInfo;

    public ChainModeDefinition(
        ChainMode mode,
        ChainPlanningStrategy planningStrategy,
        ChainActionExecutor actionExecutor,
        ChainTraverser traverser,
        ChainBlockMatcherResolver matcherResolver,
        boolean showAreaInfo,
        ChainSubMode defaultSubMode,
        List<ChainSubMode> subModes) {
        this.mode = mode;
        this.planningStrategy = planningStrategy;
        this.actionExecutor = actionExecutor;
        this.traverser = traverser;
        this.matcherResolver = matcherResolver;
        this.showAreaInfo = showAreaInfo;
        this.subModes = Collections.unmodifiableList(new ArrayList<ChainSubMode>(subModes));
        this.defaultSubMode = resolveSubMode(defaultSubMode);
    }

    public ChainMode getMode() {
        return mode;
    }

    public ChainPlanningStrategy getPlanningStrategy() {
        return planningStrategy;
    }

    public ChainActionExecutor getActionExecutor() {
        return actionExecutor;
    }

    /**
     * 获取当前模式默认使用的遍历器。
     *
     * @return 遍历器
     */
    public ChainTraverser getTraverser() {
        return traverser;
    }

    /**
     * 根据上下文创建匹配器。
     *
     * @param context 搜索上下文
     * @return 匹配器
     */
    public ChainBlockMatcher createMatcher(ChainSearchContext context) {
        return matcherResolver == null ? null : matcherResolver.createMatcher(context);
    }

    /**
     * 判断当前模式是否需要在 HUD 中显示范围信息。
     *
     * @return 是否显示范围信息
     */
    public boolean shouldShowAreaInfo() {
        return showAreaInfo;
    }

    /**
     * 获取当前主模式支持的全部子模式。
     *
     * @return 子模式列表
     */
    public List<ChainSubMode> getSubModes() {
        return subModes;
    }

    /**
     * 获取默认子模式。
     *
     * @return 默认子模式
     */
    public ChainSubMode getDefaultSubMode() {
        return defaultSubMode;
    }

    /**
     * 判断当前主模式是否支持指定子模式。
     *
     * @param subMode 子模式
     * @return 是否支持
     */
    public boolean supportsSubMode(ChainSubMode subMode) {
        return subMode != null && subModes.contains(subMode);
    }

    /**
     * 将任意子模式规范化到当前主模式支持的范围内。
     *
     * @param subMode 待规范化子模式
     * @return 规范化后的子模式
     */
    public ChainSubMode resolveSubMode(ChainSubMode subMode) {
        if (supportsSubMode(subMode)) {
            return subMode;
        }
        return subModes.isEmpty() ? null : defaultSubMode;
    }

    /**
     * 获取下一个子模式。
     *
     * @param currentSubMode 当前子模式
     * @return 下一个子模式
     */
    public ChainSubMode nextSubMode(ChainSubMode currentSubMode) {
        ChainSubMode resolvedSubMode = resolveSubMode(currentSubMode);
        if (resolvedSubMode == null) {
            return null;
        }

        int index = subModes.indexOf(resolvedSubMode);
        if (index < 0) {
            return getDefaultSubMode();
        }
        return subModes.get((index + 1) % subModes.size());
    }

    /**
     * 获取上一个子模式。
     *
     * @param currentSubMode 当前子模式
     * @return 上一个子模式
     */
    public ChainSubMode previousSubMode(ChainSubMode currentSubMode) {
        ChainSubMode resolvedSubMode = resolveSubMode(currentSubMode);
        if (resolvedSubMode == null) {
            return null;
        }

        int index = subModes.indexOf(resolvedSubMode);
        if (index < 0) {
            return getDefaultSubMode();
        }
        return subModes.get((index - 1 + subModes.size()) % subModes.size());
    }
}
