package club.heiqi.qz_miner.chain.planner;

/**
 * 候选过滤器创建器。
 */
public interface ChainCandidateFilterResolver {

    /**
     * 根据搜索上下文创建候选过滤器。
     *
     * @param context 搜索上下文
     * @return 候选过滤器
     */
    ChainCandidateFilter createFilter(ChainSearchContext context);
}
