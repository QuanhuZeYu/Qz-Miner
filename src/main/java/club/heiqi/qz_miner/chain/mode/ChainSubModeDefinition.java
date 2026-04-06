package club.heiqi.qz_miner.chain.mode;

import club.heiqi.qz_miner.chain.executor.ChainActionExecutor;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcher;
import club.heiqi.qz_miner.chain.planner.ChainBlockMatcherResolver;
import club.heiqi.qz_miner.chain.planner.ChainCandidateFilter;
import club.heiqi.qz_miner.chain.planner.ChainCandidateFilterResolver;
import club.heiqi.qz_miner.chain.planner.ChainResolverContext;
import club.heiqi.qz_miner.chain.planner.ChainSearchContext;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.planner.ChainTraverser;
import club.heiqi.qz_miner.chain.planner.ChainTraverserResolver;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * 连锁子模式定义。
 */
public final class ChainSubModeDefinition {

    private final ChainSubMode subMode;
    private final ChainSubModeTrigger trigger;
    private final ChainTraverserResolver traverserResolver;
    private final ChainBlockMatcherResolver matcherResolver;
    private final ChainCandidateFilterResolver candidateFilterResolver;
    private final ChainAreaPresentationResolver areaPresentationResolver;
    private final ChainPreviewTargetValidator previewTargetValidator;
    private final boolean remotePreview;
    private final ChainActionExecutor actionExecutor;

    public ChainSubModeDefinition(
        ChainSubMode subMode,
        ChainSubModeTrigger trigger,
        ChainTraverserResolver traverserResolver,
        ChainBlockMatcherResolver matcherResolver,
        ChainCandidateFilterResolver candidateFilterResolver,
        ChainAreaPresentationResolver areaPresentationResolver,
        ChainPreviewTargetValidator previewTargetValidator,
        boolean remotePreview,
        ChainActionExecutor actionExecutor) {
        this.subMode = subMode;
        this.trigger = trigger == null ? ChainSubModeTrigger.NONE : trigger;
        this.traverserResolver = traverserResolver;
        this.matcherResolver = matcherResolver;
        this.candidateFilterResolver = candidateFilterResolver;
        this.areaPresentationResolver = areaPresentationResolver;
        this.previewTargetValidator = previewTargetValidator;
        this.remotePreview = remotePreview;
        this.actionExecutor = actionExecutor;
    }

    public ChainSubMode getSubMode() {
        return subMode;
    }

    public ChainSubModeTrigger getTrigger() {
        return trigger;
    }

    public ChainTraverser resolveTraverser(ChainResolverContext context, ChainTraverser fallback) {
        return traverserResolver == null ? fallback : traverserResolver.createTraverser(context);
    }

    public ChainBlockMatcher resolveMatcher(ChainResolverContext context, ChainBlockMatcher fallback) {
        return matcherResolver == null ? fallback : matcherResolver.createMatcher(context);
    }

    public ChainCandidateFilter resolveCandidateFilter(ChainSearchContext context, ChainCandidateFilter fallback) {
        return candidateFilterResolver == null ? fallback : candidateFilterResolver.createFilter(context);
    }

    public int[] resolveAreaDimensions(int radius) {
        return areaPresentationResolver == null ? null : areaPresentationResolver.resolveDimensions(radius, subMode);
    }

    public boolean canPreview(World world, ChainTarget target, TileEntity sampleTileEntity) {
        return previewTargetValidator == null || previewTargetValidator.canPreview(world, target, sampleTileEntity);
    }

    public boolean usesRemotePreview() {
        return remotePreview;
    }

    public ChainActionExecutor resolveActionExecutor(ChainActionExecutor fallback) {
        return actionExecutor == null ? fallback : actionExecutor;
    }
}
