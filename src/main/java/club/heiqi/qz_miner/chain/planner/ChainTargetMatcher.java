package club.heiqi.qz_miner.chain.planner;

/**
 * 连锁目标匹配器。
 */
@FunctionalInterface
public interface ChainTargetMatcher {
    boolean matches(ChainTarget target);
}
