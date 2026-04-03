package club.heiqi.qz_miner.chain.planner;

/**
 * 连锁搜索新增目标消费者。
 */
@FunctionalInterface
public interface ChainTargetConsumer {
    void accept(ChainTarget target);
}
