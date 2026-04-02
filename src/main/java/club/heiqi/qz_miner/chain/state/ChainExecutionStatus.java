package club.heiqi.qz_miner.chain.state;

/**
 * 连锁执行状态。
 */
public enum ChainExecutionStatus {
    /**
     * 空闲。
     */
    IDLE,
    /**
     * 规划中。
     */
    PLANNING,
    /**
     * 执行中。
     */
    EXECUTING
}
