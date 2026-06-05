package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 任务订阅句柄。
 */
@FunctionalInterface
public interface ParallelTickSubscription {

    /**
     * 请求取消任务。
     */
    void unregister();
}
