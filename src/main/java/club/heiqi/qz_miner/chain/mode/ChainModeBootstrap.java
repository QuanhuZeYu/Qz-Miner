package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.chain.executor.BlockHarvestActionExecutor;
import club.heiqi.qz_miner.chain.planner.BlockFloodFillPlanningStrategy;

/**
 * 连锁模式注册引导。
 */
public final class ChainModeBootstrap {

    private ChainModeBootstrap() {}

    public static void bootstrap() {
        ChainModeRegistry.clearDefinitions();
        ChainModeRegistry.register(new ChainModeDefinition(
            ChainMode.CHAIN,
            new BlockFloodFillPlanningStrategy(),
            new BlockHarvestActionExecutor()));
    }
}
