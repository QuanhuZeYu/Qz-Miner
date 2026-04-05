package club.heiqi.qz_miner.chain.planner;

/**
 * 单次规划运行时装配结果。
 */
public final class ChainPlanningRuntime {

    private final ChainSearchContext searchContext;
    private final ChainResolverContext resolverContext;
    private final ChainCandidateFilter candidateFilter;
    private final ChainTraverser traverser;
    private final ChainBlockMatcher matcher;

    public ChainPlanningRuntime(
        ChainSearchContext searchContext,
        ChainResolverContext resolverContext,
        ChainCandidateFilter candidateFilter,
        ChainTraverser traverser,
        ChainBlockMatcher matcher) {
        this.searchContext = searchContext;
        this.resolverContext = resolverContext;
        this.candidateFilter = candidateFilter;
        this.traverser = traverser;
        this.matcher = matcher;
    }

    public ChainSearchContext getSearchContext() {
        return searchContext;
    }

    public ChainResolverContext getResolverContext() {
        return resolverContext;
    }

    public ChainCandidateFilter getCandidateFilter() {
        return candidateFilter;
    }

    public ChainTraverser getTraverser() {
        return traverser;
    }

    public ChainBlockMatcher getMatcher() {
        return matcher;
    }
}
