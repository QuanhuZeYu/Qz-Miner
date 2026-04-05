package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.executor.BlockHarvestActionExecutor;
import club.heiqi.qz_miner.chain.executor.BlockInteractActionExecutor;
import club.heiqi.qz_miner.chain.executor.NoOpActionExecutor;
import club.heiqi.qz_miner.chain.planner.AxisAlignedTunnelDirection;
import club.heiqi.qz_miner.chain.planner.BlockBoxScanPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BlockFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BoxScanTraverser;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.ChainResolverContext;
import club.heiqi.qz_miner.chain.planner.ChainTraverserResolver;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.FloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.HarvestableBlockMatcher;
import club.heiqi.qz_miner.chain.planner.InteractFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.LoggingFloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.LogBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.NoOpPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.OreBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.SameBlockMatcher;
import club.heiqi.qz_miner.chain.planner.SameBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.TunnelBoxScanTraverser;

/**
 * 连锁模式注册引导。
 */
public final class ChainModeBootstrap {

    private static final ChainAreaPresentationResolver DEFAULT_CUBE_AREA_PRESENTATION = (radius, subMode) -> {
        int sideLength = Math.max(1, radius) * 2 + 1;
        return new int[] {sideLength, sideLength, sideLength};
    };

    private static final ChainAreaPresentationResolver AREA_PRESENTATION = (radius, subMode) -> {
        if (subMode == ChainSubMode.AREA_TUNNEL) {
            return new int[] {3, 3, Math.max(1, radius)};
        }
        return DEFAULT_CUBE_AREA_PRESENTATION.resolveDimensions(radius, subMode);
    };

    private static final ChainTraverserResolver CHAIN_TRAVERSER = context -> {
        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() != null
            && context.getSearchContext().getSubMode().requiresLogMatch()) {
            return new LoggingFloodFillTraverser(Config.chainLoggingShellLayers);
        }
        return new FloodFillTraverser();
    };

    private static final ChainTraverserResolver AREA_TRAVERSER = context -> {
        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() == ChainSubMode.AREA_TUNNEL) {
            int face = context.getSession() != null && context.getSession().getRequest() != null
                ? context.getSession().getRequest().getInteractFace()
                : AxisAlignedTunnelDirection.resolveFace(context.getPlayer());
            return new TunnelBoxScanTraverser(face);
        }
        return new BoxScanTraverser();
    };

    private static final ChainTraverserResolver DEFAULT_FLOOD_FILL_TRAVERSER = context -> new FloodFillTraverser();

    private static final ChainBlockMatcherResolver CHAIN_MATCHER = context -> {
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

    private static final ChainBlockMatcherResolver SAME_BLOCK_OR_HARVESTABLE_MATCHER = context -> {
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
    
    private static final ChainBlockMatcherResolver HARVESTABLE_MATCHER = context -> new HarvestableBlockMatcher();
    private static final ChainBlockMatcherResolver INTERACT_MATCHER = context -> {
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
            CHAIN_TRAVERSER,
            CHAIN_MATCHER,
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
            AREA_TRAVERSER,
            SAME_BLOCK_OR_HARVESTABLE_MATCHER,
            true,
            AREA_PRESENTATION,
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
            DEFAULT_FLOOD_FILL_TRAVERSER,
            INTERACT_MATCHER,
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
            new NoOpPlanningStrategy(ChainMode.SPECIAL),
            new NoOpActionExecutor(ChainMode.SPECIAL),
            DEFAULT_FLOOD_FILL_TRAVERSER,
            HARVESTABLE_MATCHER,
            false,
            null,
            ChainSubMode.SPECIAL_BASE,
            Arrays.asList(ChainSubMode.SPECIAL_BASE, ChainSubMode.SPECIAL_EXTENDED));
    }
}
