package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 分片任务。
 *
 * 任务必须在单个分片内保持有界，并在安全边界响应控制对象中的取消与让出信号。
 */
@FunctionalInterface
public interface ParallelTickTask {

    /**
     * 执行一次任务分片。
     *
     * @param control 当前 Tick 控制对象
     * @return 当前分片的结构化结果
     * @throws Exception 任务执行异常
     */
    ParallelTaskResult run(ParallelTickControl control) throws Exception;

    /**
     * 请求取消时的通知钩子，任务可在此记录原因或触发轻量清理。
     *
     * @param reason 取消原因
     */
    default void onCancelRequested(String reason) {
    }

    /**
     * 任务确认终止后的清理钩子。
     */
    default void cleanupAfterTermination() {
    }
}
