package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import club.heiqi.qz_miner.chain.executor.BlockHarvestActionExecutor;
import club.heiqi.qz_miner.chain.executor.BlockInteractActionExecutor;
import club.heiqi.qz_miner.chain.executor.GregTechCableReplaceActionExecutor;
import club.heiqi.qz_miner.chain.planner.BlockBoxScanPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BlockFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.GregTechCablePlanningStrategy;
import club.heiqi.qz_miner.chain.planner.InteractFloodFillPlanningStrategy;

/**
 * 连锁模式注册引导。
 */
public final class ChainModeBootstrap {

    private ChainModeBootstrap() {}

    public static void bootstrap() {
        ChainModeRegistry.clearDefinitions();
        ChainModeRegistry.register(createChainDefinition());
        ChainModeRegistry.register(createAreaDefinition());
        ChainModeRegistry.register(createInteractDefinition());
        ChainModeRegistry.register(createSpecialDefinition());
        ChainModeRegistry.validateDefinitions();
    }

    /**
     * 创建 CHAIN 模式定义。
     *
     * @return CHAIN 模式定义
     */
    private static ChainModeDefinition createChainDefinition() {
        return new ChainModeDefinition(
            ChainMode.CHAIN,
            new BlockFloodFillPlanningStrategy(),
            new BlockHarvestActionExecutor(),
            ChainModeResolvers.CHAIN_TRAVERSER,
            ChainModeResolvers.CHAIN_MATCHER,
            false,
            null,
            ChainSubMode.CHAIN_BASE,
            Arrays.asList(ChainSubMode.CHAIN_BASE, ChainSubMode.CHAIN_ORE, ChainSubMode.CHAIN_LOGGING));
    }

    /**
     * 创建 AREA 模式定义。
     *
     * @return AREA 模式定义
     */
    private static ChainModeDefinition createAreaDefinition() {
        return new ChainModeDefinition(
            ChainMode.AREA,
            new BlockBoxScanPlanningStrategy(),
            new BlockHarvestActionExecutor(),
            ChainModeResolvers.AREA_TRAVERSER,
            ChainModeResolvers.SAME_BLOCK_OR_HARVESTABLE_MATCHER,
            true,
            ChainModeResolvers.DEFAULT_CUBE_AREA_PRESENTATION,
            ChainSubMode.AREA_SAME_BLOCK,
            Arrays.asList(ChainSubMode.AREA_SAME_BLOCK, ChainSubMode.AREA_HARVESTABLE_ALL, ChainSubMode.AREA_ORE, ChainSubMode.AREA_TUNNEL));
    }

    /**
     * 创建 INTERACT 模式定义。
     *
     * @return INTERACT 模式定义
     */
    private static ChainModeDefinition createInteractDefinition() {
        return new ChainModeDefinition(
            ChainMode.INTERACT,
            new InteractFloodFillPlanningStrategy(),
            new BlockInteractActionExecutor(),
            ChainModeResolvers.DEFAULT_FLOOD_FILL_TRAVERSER,
            ChainModeResolvers.INTERACT_MATCHER,
            false,
            null,
            ChainSubMode.INTERACT_BASE,
            Arrays.asList(ChainSubMode.INTERACT_BASE, ChainSubMode.INTERACT_CROP));
    }

    /**
     * 创建 SPECIAL 模式定义。
     *
     * @return SPECIAL 模式定义
     */
    private static ChainModeDefinition createSpecialDefinition() {
        return new ChainModeDefinition(
            ChainMode.SPECIAL,
            new GregTechCablePlanningStrategy(),
            new GregTechCableReplaceActionExecutor(),
            ChainModeResolvers.DEFAULT_FLOOD_FILL_TRAVERSER,
            ChainModeResolvers.HARVESTABLE_MATCHER,
            false,
            null,
            ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER,
            Arrays.asList(ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER, ChainSubMode.SPECIAL_GT_CABLE_REPLACE));
    }
}
