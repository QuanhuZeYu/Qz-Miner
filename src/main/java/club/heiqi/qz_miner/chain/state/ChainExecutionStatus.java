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
     * 规划与执行并行中。
     */
    RUNNING
}
