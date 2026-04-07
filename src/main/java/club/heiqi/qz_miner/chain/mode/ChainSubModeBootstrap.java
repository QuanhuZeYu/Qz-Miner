package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.executor.GregTechCableReplaceActionExecutor;
import club.heiqi.qz_miner.chain.planner.AxisAlignedTunnelDirection;
import club.heiqi.qz_miner.chain.planner.ChainBlockIdentity;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.ChainCandidateFilterResolver;
import club.heiqi.qz_miner.chain.planner.ChainLogRules;
import club.heiqi.qz_miner.chain.planner.ChainOreRules;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.ChainTraverserResolver;
import club.heiqi.qz_miner.chain.planner.CropBlockMatcher;
import club.heiqi.qz_miner.chain.planner.GregTechCableMatcher;
import club.heiqi.qz_miner.chain.planner.GregTechCableTraverser;
import club.heiqi.qz_miner.chain.planner.LogBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.LoggingFloodFillTraverser;
import club.heiqi.qz_miner.chain.planner.OreBlockHarvestableMatcher;
import club.heiqi.qz_miner.chain.planner.TunnelBoxScanTraverser;
import club.heiqi.qz_miner.compat.gregtech.GregTechCableCompatHelper;
import club.heiqi.qz_miner.compat.lootgames.LootGamesMinesweeperHelper;
import club.heiqi.qz_miner.network.PacketLootGamesMinesweeperPreviewRequest;
import ic2.core.crop.TileEntityCrop;
import net.minecraft.block.Block;
import net.minecraft.block.BlockCrops;
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
        registerInteractCropSubMode();
        registerSpecialGtCableReplaceSubMode();
        registerSpecialLootGamesMinesweeperSubMode();
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
        registerSubMode(ChainSubMode.INTERACT_BASE, ChainSubModeTrigger.RIGHT_CLICK_BLOCK, null, null, createSameBlockCandidateFilter(), null, null, null, null);
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
            TUNNEL_AREA_PRESENTATION,
            null,
            null,
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

                Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
                if (block instanceof BlockCrops) {
                    return true;
                }
                TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
                return tileEntity instanceof TileEntityCrop;
            },
            null,
            null,
            null,
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
            (world, target, sampleTileEntity) -> LootGamesMinesweeperHelper.isMinesweeperTarget(world, target),
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

    private static ChainCandidateFilterResolver createSameBlockCandidateFilter() {
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
