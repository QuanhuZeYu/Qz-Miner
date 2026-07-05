package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁规划进度事件。
 */
public final class PlanProgress extends ChainEvent {

    /** 本帧已处理的目标数。 */
    private final int processedCount;
    /** 本帧匹配命中的目标数。 */
    private final int matchedCount;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param processedCount 已处理数
     * @param matchedCount   命中数
     */
    public PlanProgress(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                        int processedCount, int matchedCount) {
        super(playerUUID, generation, serverTick, timestampNanos);
        this.processedCount = processedCount;
        this.matchedCount = matchedCount;
    }

    /** @return 已处理数 */
    public int getProcessedCount() { return processedCount; }
    /** @return 命中数 */
    public int getMatchedCount() { return matchedCount; }
}
