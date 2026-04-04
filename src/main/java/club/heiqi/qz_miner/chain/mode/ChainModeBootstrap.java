package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;

import club.heiqi.qz_miner.chain.executor.BlockHarvestActionExecutor;
import club.heiqi.qz_miner.chain.executor.NoOpActionExecutor;
import club.heiqi.qz_miner.chain.planner.BlockBoxScanPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BlockFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BoxScanTraverser;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.FloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.HarvestableBlockMatcher;
import club.heiqi.qz_miner.chain.planner.NoOpPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.SameBlockHarvestableMatcher;

/**
 * 连锁模式注册引导。
 */
public final class ChainModeBootstrap {

    private static final ChainBlockMatcherResolver SAME_BLOCK_OR_HARVESTABLE_MATCHER = context -> {
        if (context.getSubMode() != null && context.getSubMode().requiresSameBlockMatch()) {
            return new SameBlockHarvestableMatcher(context.getSampleBlock(), context.getSampleMeta());
        }
        return new HarvestableBlockMatcher();
    };

    private static final ChainBlockMatcherResolver HARVESTABLE_MATCHER = context -> new HarvestableBlockMatcher();

    private ChainModeBootstrap() {}

    public static void bootstrap() {
        ChainModeRegistry.clearDefinitions();
        ChainModeRegistry.register(new ChainModeDefinition(
            ChainMode.CHAIN,
            new BlockFloodFillPlanningStrategy(),
            new BlockHarvestActionExecutor(),
            new FloodFillTraverser(),
            HARVESTABLE_MATCHER,
            ChainSubMode.CHAIN_BASE,
            Arrays.asList(ChainSubMode.CHAIN_BASE)));
        ChainModeRegistry.register(new ChainModeDefinition(
            ChainMode.AREA,
            new BlockBoxScanPlanningStrategy(),
            new BlockHarvestActionExecutor(),
            new BoxScanTraverser(),
            SAME_BLOCK_OR_HARVESTABLE_MATCHER,
            ChainSubMode.AREA_SAME_BLOCK,
            Arrays.asList(ChainSubMode.AREA_SAME_BLOCK, ChainSubMode.AREA_HARVESTABLE_ALL)));
        ChainModeRegistry.register(new ChainModeDefinition(
            ChainMode.INTERACT,
            new NoOpPlanningStrategy(ChainMode.INTERACT),
            new NoOpActionExecutor(ChainMode.INTERACT),
            new FloodFillTraverser(),
            HARVESTABLE_MATCHER,
            ChainSubMode.INTERACT_BASE,
            Arrays.asList(ChainSubMode.INTERACT_BASE)));
        ChainModeRegistry.register(new ChainModeDefinition(
            ChainMode.SPECIAL,
            new NoOpPlanningStrategy(ChainMode.SPECIAL),
            new NoOpActionExecutor(ChainMode.SPECIAL),
            new FloodFillTraverser(),
            HARVESTABLE_MATCHER,
            ChainSubMode.SPECIAL_BASE,
            Arrays.asList(ChainSubMode.SPECIAL_BASE)));
    }
}
