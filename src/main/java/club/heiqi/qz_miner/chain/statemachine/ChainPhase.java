package club.heiqi.qz_miner.chain.statemachine;

/** 连锁状态空间；状态机统一消费事件并应用合法转移。 */
public enum ChainPhase {
    /** 空闲态：无活跃连锁，等待玩家武装。 */
    IDLE,
    /** 按键持续授权，等待一次破坏或交互观测触发规划。 */
    ARMED,
    /** 规划进行中，并行 worker 只读世界只产规划。 */
    PLANNING,
    /** 执行进行中，主线程独占真实破坏方块。 */
    RUNNING,
    /** 等待协作停止与执行结算后的生命周期收口。 */
    FINISHING
}