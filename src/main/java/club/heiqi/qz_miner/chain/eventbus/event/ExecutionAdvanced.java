package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁执行推进事件。只要本 tick 消费过目标就发布；目标均跳过或执行失败时执行数可为 0。
 */
public final class ExecutionAdvanced extends ChainEvent {

    /** 本 tick 成功执行的目标数；消费但零成功时为 0。 */
    private final int executedThisTick;
    /** 剩余待执行目标数。 */
    private final int remainingTargets;

    /**
     * @param playerUUID       触发玩家
     * @param generation       所属连锁代际
     * @param serverTick       发布时服务端 tick
     * @param timestampNanos   发布时刻纳秒戳
     * @param executedThisTick 本 tick 执行数
     * @param remainingTargets 剩余目标数
     */
    public ExecutionAdvanced(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                               int executedThisTick, int remainingTargets) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos,
                executedThisTick, remainingTargets);
    }

    /** 构造带服务端轮次关联的执行推进事件。 */
    public ExecutionAdvanced(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                              int executedThisTick, int remainingTargets) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.executedThisTick = executedThisTick;
        this.remainingTargets = remainingTargets;
    }

    /** @return 本 tick 执行数 */
    public int getExecutedThisTick() { return executedThisTick; }
    /** @return 剩余目标数 */
    public int getRemainingTargets() { return remainingTargets; }
}
