package club.heiqi.qz_miner.chain.planner;

/**
 * 候选目标过滤器。
 */
public interface ChainCandidateFilter {

    /**
     * 判断目标是否允许加入遍历候选。
     *
     * @param target 候选目标
     * @return 是否允许加入遍历
     */
    boolean canTraverse(ChainTarget target);
}
