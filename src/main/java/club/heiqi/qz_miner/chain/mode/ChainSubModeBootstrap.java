package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.executor.GregTechCableReplaceActionExecutor;
import club.heiqi.qz_miner.chain.executor.LiquidSourceInteractActionExecutor;
import club.heiqi.qz_miner.chain.executor.TargetRevalidatingBlockInteractActionExecutor;
import club.heiqi.qz_miner.chain.planner.ChainBlockIdentity;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.ChainCandidateFilterResolver;
import club.heiqi.qz_miner.chain.planner.ChainCropRules;
import club.heiqi.qz_miner.chain.planner.ChainLiquidRules;
import club.heiqi.qz_miner.chain.planner.ChainLogRules;
import club.heiqi.qz_miner.chain.planner.ChainOreRules;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.ChainTraverserResolver;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.CuboidScanTraverser;
import club.heiqi.qz_miner.chain.planner.GregTechCableMatcher;
import club.heiqi.qz_miner.chain.planner.GregTechCableTraverser;
import club.heiqi.qz_miner.chain.planner.ImmatureCropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.LiquidSourceBlockMatcher;
import club.heiqi.qz_miner.chain.planner.LogBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.LoggingFloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.OreBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.SameBlockMatcher;
import club.heiqi.qz_miner.chain.planner.SectionClearTraverser;
import club.heiqi.qz_miner.chain.planner.TunnelBoxScanTraverser;
import club.heiqi.qz_miner.compat.adapter.CompatAdapters;
import club.heiqi.qz_miner.network.PacketLootGamesMinesweeperPreviewRequest;
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;

/**
 * 连锁子模式注册引导。
 */
public final class ChainSubModeBootstrap {

    private static final ChainAreaPresentationResolver TUNNEL_AREA_PRESENTATION = (radius, subMode) -> new int[] {3, 3, Math.max(1, radius)};

    private static final ChainRemotePreviewProvider LOOTGAMES_REMOTE_PREVIEW_PROVIDER = (requestId, target, radius, maxTargets) -> {
        if (MyMod.networkMain == null || target == null) {
            return false;
        }

        MyMod.networkMain.network.sendToServer(new PacketLootGamesMinesweeperPreviewRequest(requestId, target, radius, maxTargets));
        return true;
    };

    private ChainSubModeBootstrap() {}

    public static void bootstrap() {
        ChainSubModeRegistry.clearDefinitions();
        registerDefaultSubModes();
        registerAreaTunnelSubMode();
        registerInteractSubModes();
        if (CompatAdapters.isSubModeAvailable(ChainSubMode.SPECIAL_GT_CABLE_REPLACE)) {
            registerSpecialGtCableReplaceSubMode();
        }
        if (CompatAdapters.isSubModeAvailable(ChainSubMode.SPECIAL_LOOTGAMES_MINESWEEPER)) {
            registerSpecialLootGamesMinesweeperSubMode();
        }
        registerAreaSectionClearSubMode();
        registerAreaCuboidClearSubMode();
        ChainSubModeRegistry.validateDefinitions();
    }

    private static void registerDefaultSubModes() {
        registerSubMode(ChainSubMode.CHAIN_BASE, ChainSubModeTrigger.BREAK_BLOCK, null, null, null, null, null, null, null);
        registerSubMode(ChainSubMode.CHAIN_ORE, ChainSubModeTrigger.BREAK_BLOCK, null, null, createOreCandidateFilter(), null, null, null, null);
        registerSubMode(
            ChainSubMode.CHAIN_LOGGING,
            ChainSubModeTrigger.BREAK_BLOCK,
            context -> new LoggingFloodFillTraverser(Config.chainLoggingShellLayers),
            context -> new LogBlockHarvestableMatcher(),
            createLogCandidateFilter(),
            null,
            null,
            null,
            null);
        registerSubMode(ChainSubMode.AREA_SAME_BLOCK, ChainSubModeTrigger.BREAK_BLOCK, null, null, createSameBlockCandidateFilter(), null, null, null, null);
        registerSubMode(ChainSubMode.AREA_HARVESTABLE_ALL, ChainSubModeTrigger.BREAK_BLOCK, null, null, null, null, null, null, null);
        registerSubMode(ChainSubMode.AREA_ORE, ChainSubModeTrigger.BREAK_BLOCK, null, context -> new OreBlockHarvestableMatcher(), createOreCandidateFilter(), null, null, null, null);
    }

    private static void registerAreaTunnelSubMode() {
        registerSubMode(
            ChainSubMode.AREA_TUNNEL,
            ChainSubModeTrigger.BREAK_BLOCK,
            context -> {
                int face = context != null && context.getSession() != null && context.getSession().getRequest() != null
                    ? context.getSession().getRequest().getInteractFace()
                    : 1;
                return new TunnelBoxScanTraverser(face);
            },
            null,
            null,
            TUNNEL_AREA_PRESENTATION,
            null,
            null,
            null);
    }

    /**
     * 注册 AREA 区段清理子模式。
     */
    private static void registerAreaSectionClearSubMode() {
        registerSubMode(
            ChainSubMode.AREA_SECTION_CLEAR,
            ChainSubModeTrigger.BREAK_BLOCK,
            context -> new SectionClearTraverser(),
            null,
            null,
            (radius, subMode) -> new int[] {16, 16, 16},
            null,
            null,
            null);
    }

    /** 注册只扫描单次请求中冻结选区的 AREA 子模式。 */
    private static void registerAreaCuboidClearSubMode() {
        registerSubMode(
            ChainSubMode.AREA_CUBOID_CLEAR,
            ChainSubModeTrigger.BREAK_BLOCK,
            context -> context == null || context.getSession() == null
                    || context.getSession().getRequest() == null
                    || context.getSession().getRequest().getCuboidBounds() == null
                            ? null
                            : new CuboidScanTraverser(context.getSession().getRequest().getCuboidBounds()),
            null,
            null,
            null,
            null,
            null,
            null);
    }

    /** 注册四种预算化范围交互子模式。 */
    private static void registerInteractSubModes() {
        registerSubMode(
            ChainSubMode.INTERACT_BASE,
            ChainSubModeTrigger.RIGHT_CLICK,
            ChainModeResolvers.AREA_TRAVERSER,
            createInteractSameBlockMatcherResolver(),
            createSameBlockCandidateFilter(),
            null,
            null,
            null,
            new TargetRevalidatingBlockInteractActionExecutor(ChainSubMode.INTERACT_BASE));
        registerSubMode(
            ChainSubMode.INTERACT_LIQUID_SOURCE,
            ChainSubModeTrigger.RIGHT_CLICK,
            ChainModeResolvers.AREA_TRAVERSER,
            createLiquidSourceMatcherResolver(),
            createLiquidSourceCandidateFilter(),
            null,
            null,
            null,
            new LiquidSourceInteractActionExecutor());
        registerSubMode(
            ChainSubMode.INTERACT_CROP,
            ChainSubModeTrigger.RIGHT_CLICK,
            ChainModeResolvers.AREA_TRAVERSER,
            context -> new CropBlockMatcher(),
            createCropCandidateFilter(),
            null,
            null,
            null,
            new TargetRevalidatingBlockInteractActionExecutor(ChainSubMode.INTERACT_CROP));
        registerSubMode(
            ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP,
            ChainSubModeTrigger.RIGHT_CLICK,
            ChainModeResolvers.AREA_TRAVERSER,
            context -> new ImmatureCropBlockMatcher(),
            createImmatureCropCandidateFilter(),
            null,
            null,
            null,
            new TargetRevalidatingBlockInteractActionExecutor(
                ChainSubMode.INTERACT_FERTILIZE_IMMATURE_CROP));
    }

    private static void registerSpecialGtCableReplaceSubMode() {
        registerSubMode(
            ChainSubMode.SPECIAL_GT_CABLE_REPLACE,
            ChainSubModeTrigger.LEFT_CLICK_BLOCK,
            context -> new GregTechCableTraverser(context == null ? null : context.getSession()),
            context -> new GregTechCableMatcher(context == null || context.getSearchContext() == null ? -1
                : CompatAdapters.cable().getCableMetaTileId(context.getSearchContext().getSampleTileEntity())),
            context -> target -> {
                if (context == null || target == null) {
                    return false;
                }
                return CompatAdapters.cable().isCable(context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ()));
            },
            null,
            (world, target, sampleTileEntity) -> CompatAdapters.cable().isCable(sampleTileEntity),
            null,
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
            (world, target, sampleTileEntity) -> CompatAdapters.minesweeper().isMinesweeperTarget(world, target),
            LOOTGAMES_REMOTE_PREVIEW_PROVIDER,
            null);
    }

    private static ChainCandidateFilterResolver createOreCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null) {
                return false;
            }

            Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
            TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
            return ChainOreRules.isOreBlock(block, tileEntity);
        };
    }

    private static ChainCandidateFilterResolver createLogCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null) {
                return false;
            }

            Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
            int meta = context.getWorld().getBlockMetadata(target.getX(), target.getY(), target.getZ());
            return ChainLogRules.isLogBlock(context.getWorld(), target.getX(), target.getY(), target.getZ(), block, meta);
        };
    }

    private static ChainBlockMatcherResolver createInteractSameBlockMatcherResolver() {
        return context -> {
            ChainSearchContext searchContext = context == null ? null : context.getSearchContext();
            return new SameBlockMatcher(
                searchContext == null ? null : searchContext.getSampleBlock(),
                searchContext == null ? 0 : searchContext.getSampleMeta(),
                searchContext == null ? null : searchContext.getSampleTileIdentity());
        };
    }

    private static ChainBlockMatcherResolver createLiquidSourceMatcherResolver() {
        return context -> {
            ChainSearchContext searchContext = context == null ? null : context.getSearchContext();
            return new LiquidSourceBlockMatcher(
                searchContext == null ? null : searchContext.getSampleBlock(),
                searchContext == null ? 0 : searchContext.getSampleMeta());
        };
    }

    private static ChainCandidateFilterResolver createLiquidSourceCandidateFilter() {
        return context -> {
            if (context == null) {
                return target -> false;
            }
            final Block seedBlock = context.getSampleBlock();
            final int seedMetadata = context.getSampleMeta();
            final String seedFluidIdentity = ChainLiquidRules.fluidIdentity(seedBlock);
            final boolean seedSource = ChainLiquidRules.isSeedSource(seedBlock, seedMetadata);
            return target -> {
                if (!seedSource || seedFluidIdentity == null || target == null || context.getWorld() == null) {
                    return false;
                }
                try {
                    Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
                    int metadata = context.getWorld().getBlockMetadata(
                        target.getX(), target.getY(), target.getZ());
                    return ChainLiquidRules.matchesSource(
                        seedFluidIdentity,
                        context.getWorld(),
                        target.getX(),
                        target.getY(),
                        target.getZ(),
                        block,
                        metadata);
                } catch (RuntimeException | LinkageError failure) {
                    return false;
                }
            };
        };
    }

    private static ChainCandidateFilterResolver createCropCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null || context.getWorld() == null) {
                return false;
            }
            try {
                Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
                TileEntity tileEntity = context.getWorld().getTileEntity(
                    target.getX(), target.getY(), target.getZ());
                return ChainCropRules.isCropBlock(block, tileEntity);
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        };
    }

    private static ChainCandidateFilterResolver createImmatureCropCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null || context.getWorld() == null) {
                return false;
            }
            try {
                Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
                int metadata = context.getWorld().getBlockMetadata(
                    target.getX(), target.getY(), target.getZ());
                TileEntity tileEntity = context.getWorld().getTileEntity(
                    target.getX(), target.getY(), target.getZ());
                return ChainCropRules.isReliablyImmature(
                    context.getWorld(),
                    target.getX(),
                    target.getY(),
                    target.getZ(),
                    block,
                    metadata,
                    tileEntity);
            } catch (RuntimeException | LinkageError failure) {
                return false;
            }
        };
    }

    private static ChainCandidateFilterResolver createSameBlockCandidateFilter() {
        return context -> target -> {
            if (context == null || target == null) {
                return false;
            }

            return ChainBlockIdentity.matches(
                context.getWorld(),
                context.getSampleBlock(),
                context.getSampleMeta(),
                context.getSampleTileIdentity(),
                target);
        };
    }

    private static void registerSubMode(
        ChainSubMode subMode,
        ChainSubModeTrigger trigger,
        ChainTraverserResolver traverserResolver,
        ChainBlockMatcherResolver matcherResolver,
        ChainCandidateFilterResolver candidateFilterResolver,
        ChainAreaPresentationResolver areaPresentationResolver,
        ChainPreviewTargetValidator previewTargetValidator,
        ChainRemotePreviewProvider remotePreviewProvider,
        ChainActionExecutor actionExecutor) {
        ChainSubModeRegistry.register(new ChainSubModeDefinition(
            subMode,
            trigger,
            traverserResolver,
            matcherResolver,
            candidateFilterResolver,
            areaPresentationResolver,
            previewTargetValidator,
            remotePreviewProvider,
            actionExecutor));
    }
}
