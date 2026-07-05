package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁规划被取消事件。
 */
public final class PlanCancelled extends ChainEvent {

    /** 取消原因（自由文本，用于诊断/HUD）。 */
    private final String reason;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param reason         取消原因
     */
    public PlanCancelled(UUID playerUUID, int generation, long serverTick, long timestampNanos, String reason) {
        super(playerUUID, generation, serverTick, timestampNanos);
        this.reason = reason;
    }

    /** @return 取消原因 */
    public String getReason() { return reason; }
}
