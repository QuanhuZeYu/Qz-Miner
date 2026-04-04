package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import club.heiqi.qz_miner.chain.executor.BlockHarvestActionExecutor;
import club.heiqi.qz_miner.chain.executor.BlockInteractActionExecutor;
import club.heiqi.qz_miner.chain.executor.NoOpActionExecutor;
import club.heiqi.qz_miner.chain.planner.BlockBoxScanPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BlockFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BoxScanTraverser;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.FloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.HarvestableBlockMatcher;
import club.heiqi.qz_miner.chain.planner.InteractFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.NoOpPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.SameBlockMatcher;
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
    private static final ChainBlockMatcherResolver INTERACT_MATCHER = context -> {
        if (context.getSubMode() == ChainSubMode.INTERACT_CROP) {
            return new CropBlockMatcher();
        }
        return new SameBlockMatcher(context.getSampleBlock(), context.getSampleMeta());
    };

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
            new FloodFillTraverser(),
            HARVESTABLE_MATCHER,
            false,
            ChainSubMode.CHAIN_BASE,
            Arrays.asList(ChainSubMode.CHAIN_BASE));
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
            new BoxScanTraverser(),
            SAME_BLOCK_OR_HARVESTABLE_MATCHER,
            true,
            ChainSubMode.AREA_SAME_BLOCK,
            Arrays.asList(ChainSubMode.AREA_SAME_BLOCK, ChainSubMode.AREA_HARVESTABLE_ALL));
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
            new FloodFillTraverser(),
            INTERACT_MATCHER,
            false,
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
            new NoOpPlanningStrategy(ChainMode.SPECIAL),
            new NoOpActionExecutor(ChainMode.SPECIAL),
            new FloodFillTraverser(),
            HARVESTABLE_MATCHER,
            false,
            ChainSubMode.SPECIAL_BASE,
            Arrays.asList(ChainSubMode.SPECIAL_BASE, ChainSubMode.SPECIAL_EXTENDED));
    }
}
