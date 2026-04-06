package club.heiqi.qz_miner.chain.mode;

import java.util.Arrays;
import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.compat.gregtech.GregTechCableCompatHelper;
import club.heiqi.qz_miner.compat.lootgames.LootGamesMinesweeperHelper;
import club.heiqi.qz_miner.chain.executor.BlockHarvestActionExecutor;
import club.heiqi.qz_miner.chain.executor.BlockInteractActionExecutor;
import club.heiqi.qz_miner.chain.executor.GregTechCableReplaceActionExecutor;
import club.heiqi.qz_miner.chain.planner.AxisAlignedTunnelDirection;
import club.heiqi.qz_miner.chain.planner.BlockBoxScanPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.BlockFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.ChainBlockIdentity;
import club.heiqi.qz_miner.chain.planner.GregTechCableMatcher;
import club.heiqi.qz_miner.chain.planner.GregTechCablePlanningStrategy;
import club.heiqi.qz_miner.chain.planner.GregTechCableTraverser;
import club.heiqi.qz_miner.chain.planner.BoxScanTraverser;
import club.heiqi.qz_miner.chain.planner.ChainCandidateFilter;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.ChainResolverContext;
import club.heiqi.qz_miner.chain.planner.ChainTraverserResolver;
import club.heiqi.qz_miner.chain.planner.ChainLogRules;
import club.heiqi.qz_miner.chain.planner.ChainOreRules;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.FloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.HarvestableBlockMatcher;
import club.heiqi.qz_miner.chain.planner.InteractFloodFillPlanningStrategy;
import club.heiqi.qz_miner.chain.planner.LoggingFloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.LogBlockHarvestableMatcher;
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

    private static final ChainTraverserResolver CHAIN_TRAVERSER = context -> {
        if (context != null
            && context.getSearchContext() != null
            && context.getSearchContext().getSubMode() != null
            && context.getSearchContext().getSubMode().requiresLogMatch()) {
            return new LoggingFloodFillTraverser(Config.chainLoggingShellLayers);
        }
        return new FloodFillTraverser();
    };

    private static final ChainTraverserResolver AREA_TRAVERSER = context -> new BoxScanTraverser();

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
        ChainSubModeRegistry.clearDefinitions();
        ChainModeRegistry.register(createChainDefinition());
        ChainModeRegistry.register(createAreaDefinition());
        ChainModeRegistry.register(createInteractDefinition());
        ChainModeRegistry.register(createSpecialDefinition());
        registerSubModeDefinitions();
        ChainModeRegistry.validateDefinitions();
        ChainSubModeRegistry.validateDefinitions();
    }

    private static void registerSubModeDefinitions() {
        registerDefaultSubModes();
        registerAreaTunnelSubMode();
        registerInteractCropSubMode();
        registerSpecialGtCableReplaceSubMode();
        registerSpecialLootGamesMinesweeperSubMode();
    }

    private static void registerDefaultSubModes() {
        registerSubMode(ChainSubMode.CHAIN_BASE, ChainSubModeTrigger.BREAK_BLOCK, null, null, null, null, null, false, null);
        registerSubMode(ChainSubMode.CHAIN_ORE, ChainSubModeTrigger.BREAK_BLOCK, null, null, createOreCandidateFilter(), null, null, false, null);
        registerSubMode(
            ChainSubMode.CHAIN_LOGGING,
            ChainSubModeTrigger.BREAK_BLOCK,
            context -> new LoggingFloodFillTraverser(Config.chainLoggingShellLayers),
            context -> new LogBlockHarvestableMatcher(),
            createLogCandidateFilter(),
            null,
            null,
            false,
            null);
        registerSubMode(ChainSubMode.AREA_SAME_BLOCK, ChainSubModeTrigger.BREAK_BLOCK, null, null, createSameBlockCandidateFilter(), null, null, false, null);
        registerSubMode(ChainSubMode.AREA_HARVESTABLE_ALL, ChainSubModeTrigger.BREAK_BLOCK, null, null, null, null, null, false, null);
        registerSubMode(ChainSubMode.AREA_ORE, ChainSubModeTrigger.BREAK_BLOCK, null, context -> new OreBlockHarvestableMatcher(), createOreCandidateFilter(), null, null, false, null);
        registerSubMode(ChainSubMode.INTERACT_BASE, ChainSubModeTrigger.RIGHT_CLICK_BLOCK, null, null, createSameBlockCandidateFilter(), null, null, false, null);
    }

    private static void registerAreaTunnelSubMode() {
        registerSubMode(
            ChainSubMode.AREA_TUNNEL,
            ChainSubModeTrigger.BREAK_BLOCK,
            context -> {
                int face = context != null && context.getSession() != null && context.getSession().getRequest() != null
                    ? context.getSession().getRequest().getInteractFace()
                    : AxisAlignedTunnelDirection.resolveFace(context == null ? null : context.getPlayer());
                return new TunnelBoxScanTraverser(face);
            },
            null,
            null,
            (radius, subMode) -> new int[] {3, 3, Math.max(1, radius)},
            null,
            false,
            null);
    }

    private static void registerInteractCropSubMode() {
        registerSubMode(
            ChainSubMode.INTERACT_CROP,
            ChainSubModeTrigger.RIGHT_CLICK_BLOCK,
            null,
            context -> new CropBlockMatcher(),
            context -> target -> {
                if (context == null || target == null) {
                    return false;
                }

                net.minecraft.block.Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
                if (block instanceof net.minecraft.block.BlockCrops) {
                    return true;
                }
                net.minecraft.tileentity.TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
                return tileEntity instanceof ic2.core.crop.TileEntityCrop;
            },
            null,
            null,
            false,
            null);
    }

    private static void registerSpecialGtCableReplaceSubMode() {
        registerSubMode(
            ChainSubMode.SPECIAL_GT_CABLE_REPLACE,
            ChainSubModeTrigger.LEFT_CLICK_BLOCK,
            context -> new GregTechCableTraverser(),
            context -> new GregTechCableMatcher(context == null || context.getSearchContext() == null ? -1
                : GregTechCableCompatHelper.getCableMetaTileId(context.getSearchContext().getSampleTileEntity())),
            context -> target -> {
                if (context == null || target == null) {
                    return false;
                }
                return GregTechCableCompatHelper.isCable(context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ()));
            },
            null,
            (world, target, sampleTileEntity) -> GregTechCableCompatHelper.isCable(sampleTileEntity),
            false,
            new GregTechCableReplaceActionExecutor());
    }

    private static void registerSpecialLootGamesMinesweeperSubMode() {
        registerSubMode(
            ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER,
            ChainSubModeTrigger.NONE,
            null,
            null,
            null,
            null,
            (world, target, sampleTileEntity) -> LootGamesMinesweeperHelper.isMinesweeperTarget(world, target),
            true,
            null);
    }

    private static club.heiqi.qz_miner.chain.planner.ChainCandidateFilterResolver createOreCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null) {
                return false;
            }

            net.minecraft.block.Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
            net.minecraft.tileentity.TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
            return ChainOreRules.isOreBlock(block, tileEntity);
        };
    }

    private static club.heiqi.qz_miner.chain.planner.ChainCandidateFilterResolver createLogCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null) {
                return false;
            }

            net.minecraft.block.Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
            int meta = context.getWorld().getBlockMetadata(target.getX(), target.getY(), target.getZ());
            return ChainLogRules.isLogBlock(context.getWorld(), target.getX(), target.getY(), target.getZ(), block, meta);
        };
    }

    private static club.heiqi.qz_miner.chain.planner.ChainCandidateFilterResolver createSameBlockCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null) {
                return false;
            }

            return ChainBlockIdentity.matches(
                context.getWorld(),
                context.getSampleBlock(),
                context.getSampleMeta(),
                context.getSampleTileEntity(),
                target);
        };
    }

    private static void registerSubMode(
        ChainSubMode subMode,
        ChainSubModeTrigger trigger,
        ChainTraverserResolver traverserResolver,
        ChainBlockMatcherResolver matcherResolver,
        club.heiqi.qz_miner.chain.planner.ChainCandidateFilterResolver candidateFilterResolver,
        ChainAreaPresentationResolver areaPresentationResolver,
        ChainPreviewTargetValidator previewTargetValidator,
        boolean remotePreview,
        club.heiqi.qz_miner.chain.executor.ChainActionExecutor actionExecutor) {
        ChainSubModeRegistry.register(new ChainSubModeDefinition(
            subMode,
            trigger,
            traverserResolver,
            matcherResolver,
            candidateFilterResolver,
            areaPresentationResolver,
            previewTargetValidator,
            remotePreview,
            actionExecutor));
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
            DEFAULT_CUBE_AREA_PRESENTATION,
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
            new GregTechCablePlanningStrategy(),
            new GregTechCableReplaceActionExecutor(),
            DEFAULT_FLOOD_FILL_TRAVERSER,
            HARVESTABLE_MATCHER,
            false,
            null,
            ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER,
            Arrays.asList(ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER, ChainSubMode.SPECIAL_GT_CABLE_REPLACE));
    }
}
