package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 玩家连锁状态清理事件（对应 cleanupPlayerState 语义，守 NORTH_STAR 不变量 I7）。
 */
public final class LifecycleCleanup extends ChainEvent {

    /** 清理原因。 */
    private final String reason;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param reason         清理原因
     */
    public LifecycleCleanup(UUID playerUUID, int generation, long serverTick, long timestampNanos, String reason) {
        super(playerUUID, generation, serverTick, timestampNanos);
        this.reason = reason;
    }

    /** @return 清理原因 */
    public String getReason() { return reason; }
}
