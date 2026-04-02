package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 任务订阅句柄。
 */
@FunctionalInterface
public interface ParallelTickSubscription {

    /**
     * 注销任务。
     */
    void unregister();
}
