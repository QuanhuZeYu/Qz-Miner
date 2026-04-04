package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.planner.ChainPlanningStrategy;

/**
 * 连锁模式定义。
 */
public final class ChainModeDefinition {

    private final ChainMode mode;
    private final ChainPlanningStrategy planningStrategy;
    private final ChainActionExecutor actionExecutor;

    public ChainModeDefinition(ChainMode mode, ChainPlanningStrategy planningStrategy, ChainActionExecutor actionExecutor) {
        this.mode = mode;
        this.planningStrategy = planningStrategy;
        this.actionExecutor = actionExecutor;
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
}
