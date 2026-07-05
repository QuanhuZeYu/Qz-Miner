package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁规划开始事件。无自有字段，仅承载父类代际/tick/玩家元数据。
 */
public final class PlanStarted extends ChainEvent {

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     */
    public PlanStarted(UUID playerUUID, int generation, long serverTick, long timestampNanos) {
        super(playerUUID, generation, serverTick, timestampNanos);
    }
}
