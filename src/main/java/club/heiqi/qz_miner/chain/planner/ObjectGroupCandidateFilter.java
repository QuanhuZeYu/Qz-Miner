package club.heiqi.qz_miner.chain.planner;

import club.heiqi.qz_miner.objectgroup.ObjectGroup;

/**
 * 对象组候选过滤器，与 {@link ObjectGroupMatcher} 共用同一显式身份谓词。
 */
public final class ObjectGroupCandidateFilter implements ChainCandidateFilter {

    private final ObjectGroupBlockPredicate predicate;
    private final ChainSearchContext context;

    public ObjectGroupCandidateFilter(ChainSearchContext context, ObjectGroup group) {
        this(context, new ObjectGroupBlockPredicate(group));
    }

    public ObjectGroupCandidateFilter(ChainSearchContext context, ObjectGroupBlockPredicate predicate) {
        this.context = context;
        if (predicate == null) {
            throw new IllegalArgumentException("predicate must not be null");
        }
        this.predicate = predicate;
    }

    @Override
    public boolean canTraverse(ChainTarget target) {
        return context != null && predicate.matches(context.getWorld(), target);
    }
}
