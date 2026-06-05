package club.heiqi.qz_miner.parallel;

/**
 * 并行 Tick 任务单个分片的结构化结果。
 */
public enum ParallelTaskResult {
    /**
     * 当前分片已完成一段工作，窗口仍可用时允许继续执行下一分片。
     */
    CONTINUE,

    /**
     * 当前分片已主动让出，后续 Tick 再继续执行。
     */
    YIELDED,

    /**
     * 任务已经自然完成。
     */
    COMPLETED,

    /**
     * 任务已经响应取消请求并安全终止。
     */
    TERMINATED
}
