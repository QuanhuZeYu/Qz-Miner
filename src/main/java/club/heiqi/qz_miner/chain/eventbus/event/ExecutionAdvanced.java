package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁执行推进事件（每 tick 0~N 个目标）。
 */
public final class ExecutionAdvanced extends ChainEvent {

    /** 本 tick 已执行的目标数。 */
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
        super(playerUUID, generation, serverTick, timestampNanos);
        this.executedThisTick = executedThisTick;
        this.remainingTargets = remainingTargets;
    }

    /** @return 本 tick 执行数 */
    public int getExecutedThisTick() { return executedThisTick; }
    /** @return 剩余目标数 */
    public int getRemainingTargets() { return remainingTargets; }
}
