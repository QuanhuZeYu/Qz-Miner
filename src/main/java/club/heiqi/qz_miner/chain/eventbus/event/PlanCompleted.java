package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁规划完成事件。
 */
public final class PlanCompleted extends ChainEvent {

    /** 规划确定的目标总数。 */
    private final int totalTargets;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param totalTargets   目标总数
     */
    public PlanCompleted(UUID playerUUID, int generation, long serverTick, long timestampNanos, int totalTargets) {
        super(playerUUID, generation, serverTick, timestampNanos);
        this.totalTargets = totalTargets;
    }

    /** @return 目标总数 */
    public int getTotalTargets() { return totalTargets; }
}
