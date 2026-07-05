package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁执行结束事件。
 */
public final class ExecutionFinished extends ChainEvent {

    /** 结束原因（完成/中断/异常等）。 */
    private final String reason;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param reason         结束原因
     */
    public ExecutionFinished(UUID playerUUID, int generation, long serverTick, long timestampNanos, String reason) {
        super(playerUUID, generation, serverTick, timestampNanos);
        this.reason = reason;
    }

    /** @return 结束原因 */
    public String getReason() { return reason; }
}
