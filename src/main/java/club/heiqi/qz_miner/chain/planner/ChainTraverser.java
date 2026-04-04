package club.heiqi.qz_miner.chain.planner;

/**
 * 连锁遍历器。
 */
public interface ChainTraverser {

    void seed(ChainSearchContext context);

    boolean step(ChainSearchContext context, int maxNodes, ChainTargetMatcher matcher, ChainTargetConsumer consumer);
}
