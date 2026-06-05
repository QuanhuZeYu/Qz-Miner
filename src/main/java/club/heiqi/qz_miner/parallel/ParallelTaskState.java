package club.heiqi.qz_miner.parallel;

/**
 * 已注册并行 Tick 任务的生命周期状态。
 */
public enum ParallelTaskState {
    /**
     * 任务已注册，等待可用并行窗口。
     */
    REGISTERED,

    /**
     * Worker 正在执行任务分片。
     */
    RUNNING,

    /**
     * 任务已在安全边界主动让出。
     */
    YIELDED,

    /**
     * 外部已经请求取消，等待任务在安全点响应。
     */
    CANCEL_REQUESTED,

    /**
     * 任务正在执行终止清理。
     */
    TERMINATING,

    /**
     * 任务已经安全终止。
     */
    TERMINATED,

    /**
     * 任务已经自然完成。
     */
    COMPLETED,

    /**
     * 任务异常失败。
     */
    FAILED
}
