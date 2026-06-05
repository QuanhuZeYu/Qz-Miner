package club.heiqi.qz_miner.chain.planner;

/**
 * 连锁遍历器单个分片的结构化结果。
 */
public enum TraversalStepResult {
    /**
     * 当前分片完成一段工作，窗口仍可用时允许继续执行下一分片。
     */
    CONTINUE,

    /**
     * 当前分片已在安全边界主动让出。
     */
    YIELDED,

    /**
     * 遍历已经自然完成。
     */
    COMPLETED,

    /**
     * 遍历已经响应取消并安全终止。
     */
    TERMINATED
}
