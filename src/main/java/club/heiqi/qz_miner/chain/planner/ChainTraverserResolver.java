package club.heiqi.qz_miner.chain.planner;

/**
 * 连锁遍历器解析器。
 */
public interface ChainTraverserResolver {

    /**
     * 根据解析上下文创建遍历器。
     *
     * @param context 解析上下文
     * @return 遍历器
     */
    BudgetedChainTraverser createTraverser(ChainResolverContext context);
}
