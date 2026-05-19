package club.heiqi.qz_miner.chain.planner;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.chain.mode.ChainModeDefinition;
import club.heiqi.qz_miner.chain.mode.ChainModeRegistry;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.mode.ChainSubModeRegistry;
import club.heiqi.qz_miner.chain.state.ChainSession;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 规划运行时装配工厂。
 */
public final class ChainPlanningRuntimeFactory {

    private ChainPlanningRuntimeFactory() {}

    public static ChainPlanningRuntime createForServer(
        World world,
        EntityPlayer player,
        ChainSession session,
        BlockSeedSnapshot seedSnapshot) {
        if (world == null || session == null || seedSnapshot == null) {
            return null;
        }

        session.getRuntimeState().getTraversalTargets().clear();
        int requestedRadius = session.getRequest().getRequestedChainRadius();
        int requestedMaxBlocks = session.getRequest().getRequestedChainMaxBlocks();
        int effectiveRadius = requestedRadius > 0 ? Math.min(Config.chainRadius, requestedRadius) : Config.chainRadius;
        int effectiveMaxBlocks = requestedMaxBlocks > 0 ? Math.min(Config.chainMaxBlocks, requestedMaxBlocks) : Config.chainMaxBlocks;
        ChainSearchContext searchContext = createSearchContext(
            world,
            seedSnapshot,
            session.getRequest().getSubMode(),
            effectiveRadius,
            effectiveMaxBlocks,
            session.getRuntimeState().getTraversalTargets());

        return createRuntime(player, session, searchContext, session.getRequest().getMode());
    }

    public static ChainPlanningRuntime createForPreview(
        World world,
        EntityPlayer player,
        ChainSession session,
        BlockSeedSnapshot seedSnapshot,
        int maxRadius,
        int maxTargets) {
        if (world == null || seedSnapshot == null || session == null) {
            return null;
        }

        ChainSearchContext searchContext = createSearchContext(
            world,
            seedSnapshot,
            session.getRequest().getSubMode(),
            maxRadius,
            maxTargets,
            new ConcurrentLinkedQueue<ChainTarget>());

        return createRuntime(player, session, searchContext, session.getRequest().getMode());
    }

    private static ChainPlanningRuntime createRuntime(
        EntityPlayer player,
        ChainSession session,
        ChainSearchContext searchContext,
        club.heiqi.qz_miner.chain.mode.ChainMode mode) {
        ChainModeDefinition definition = ChainModeRegistry.getDefinition(mode);
        if (definition == null || searchContext == null) {
            return null;
        }

        ChainResolverContext resolverContext = new ChainResolverContext(player, session, searchContext);
        ChainCandidateFilter candidateFilter = createCandidateFilter(searchContext);
        searchContext.setCandidateFilter(candidateFilter);

        ChainTraverser traverser = definition.createTraverser(resolverContext);
        ChainBlockMatcher matcher = definition.createMatcher(resolverContext);
        if (candidateFilter == null || traverser == null || matcher == null) {
            return null;
        }

        return new ChainPlanningRuntime(searchContext, resolverContext, candidateFilter, traverser, matcher);
    }

    private static ChainSearchContext createSearchContext(
        World world,
        BlockSeedSnapshot seedSnapshot,
        ChainSubMode subMode,
        int maxRadius,
        int maxTargets,
        ConcurrentLinkedQueue<ChainTarget> currentFrontier) {
        ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<ChainTarget>();
        Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        return new ChainSearchContext(
            world,
            seedSnapshot.getOrigin(),
            seedSnapshot.getSampleBlock(),
            seedSnapshot.getSampleMeta(),
            seedSnapshot.getSampleTileEntity(),
            subMode,
            maxRadius,
            maxTargets,
            currentFrontier,
            nextFrontier,
            visited);
    }

    private static ChainCandidateFilter createCandidateFilter(final ChainSearchContext context) {
        ChainCandidateFilter fallback = target -> {
            if (context == null || target == null) {
                return false;
            }

            Block block = context.getWorld().getBlock(target.getX(), target.getY(), target.getZ());
            if (block == null || block == Blocks.air) {
                return false;
            }

            ChainSubMode subMode = context.getSubMode();
            if (subMode == ChainSubMode.INTERACT_CROP) {
                TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
                return ChainCropRules.isCropBlock(block, tileEntity);
            }

            if (subMode != null && subMode.requiresOreMatch()) {
                TileEntity tileEntity = context.getWorld().getTileEntity(target.getX(), target.getY(), target.getZ());
                return ChainOreRules.isOreBlock(block, tileEntity);
            }

            if (subMode != null && subMode.requiresLogMatch()) {
                int meta = context.getWorld().getBlockMetadata(target.getX(), target.getY(), target.getZ());
                return ChainLogRules.isLogBlock(context.getWorld(), target.getX(), target.getY(), target.getZ(), block, meta);
            }

            if (subMode != null && subMode.requiresSameBlockMatch()) {
                return ChainBlockIdentity.matches(
                    context.getWorld(),
                    context.getSampleBlock(),
                    context.getSampleMeta(),
                    context.getSampleTileEntity(),
                    target);
            }

            return true;
        };
        return ChainSubModeRegistry.createCandidateFilter(context, fallback);
    }
}
