package club.heiqi.qz_miner.chain.planner;

/**
 * 方块匹配器创建器。
 */
public interface ChainBlockMatcherResolver {

    /**
     * 根据搜索上下文创建匹配器。
     *
     * @param context 搜索上下文
     * @return 匹配器
     */
    ChainBlockMatcher createMatcher(ChainSearchContext context);
}
