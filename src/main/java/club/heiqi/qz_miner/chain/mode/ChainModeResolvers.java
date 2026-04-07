package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.planner.BoxScanTraverser;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.ChainTraverserResolver;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.FloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.HarvestableBlockMatcher;
import club.heiqi.qz_miner.chain.planner.LoggingFloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.LogBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.OreBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.SameBlockMatcher;
import club.heiqi.qz_miner.chain.planner.SameBlockHarvestableMatcher;

/**
 * 主模式默认解析器集合。
 */
public final class ChainModeResolvers {

    public static final ChainAreaPresentationResolver DEFAULT_CUBE_AREA_PRESENTATION = (radius, subMode) -> {
        int sideLength = Math.max(1, radius) * 2 + 1;
        return new int[] {sideLength, sideLength, sideLength};
    };

    public static final ChainTraverserResolver CHAIN_TRAVERSER = context -> {
        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() != null
            && context.getSearchContext().getSubMode().requiresLogMatch()) {
            return new LoggingFloodFillTraverser(Config.chainLoggingShellLayers);
        }
        return new FloodFillTraverser();
    };

    public static final ChainTraverserResolver AREA_TRAVERSER = context -> new BoxScanTraverser();

    public static final ChainTraverserResolver DEFAULT_FLOOD_FILL_TRAVERSER = context -> new FloodFillTraverser();

    public static final ChainBlockMatcherResolver CHAIN_MATCHER = context -> {
        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() != null
            && context.getSearchContext().getSubMode().requiresLogMatch()) {
            return new LogBlockHarvestableMatcher();
        }

        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() != null
            && context.getSearchContext().getSubMode().requiresOreMatch()) {
            return new OreBlockHarvestableMatcher();
        }
        return new HarvestableBlockMatcher();
    };

    public static final ChainBlockMatcherResolver SAME_BLOCK_OR_HARVESTABLE_MATCHER = context -> {
        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() != null
            && context.getSearchContext().getSubMode().requiresOreMatch()) {
            return new OreBlockHarvestableMatcher();
        }

        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() != null
            && context.getSearchContext().getSubMode().requiresSameBlockMatch()) {
            return new SameBlockHarvestableMatcher(
                context.getSearchContext().getSampleBlock(),
                context.getSearchContext().getSampleMeta(),
                context.getSearchContext().getSampleTileEntity());
        }
        return new HarvestableBlockMatcher();
    };

    public static final ChainBlockMatcherResolver HARVESTABLE_MATCHER = context -> new HarvestableBlockMatcher();

    public static final ChainBlockMatcherResolver INTERACT_MATCHER = context -> {
        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() == ChainSubMode.INTERACT_CROP) {
            return new CropBlockMatcher();
        }
        return new SameBlockMatcher(
            context.getSearchContext().getSampleBlock(),
            context.getSearchContext().getSampleMeta(),
            context.getSearchContext().getSampleTileEntity());
    };

    private ChainModeResolvers() {}
}
