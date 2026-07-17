package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 看门狗超时事件（FINISHING/看门狗，守 NORTH_STAR 不变量 I2/I9 显式收尾）。
 */
public final class WatchdogTimeout extends ChainEvent {

    /** 已耗用纳秒数。 */
    private final long elapsedNanos;

    /**
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param elapsedNanos   已耗用纳秒
     */
    public WatchdogTimeout(UUID playerUUID, int generation, long serverTick, long timestampNanos, long elapsedNanos) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos, elapsedNanos);
    }

    /** 构造带服务端轮次关联的看门狗超时事件。 */
    public WatchdogTimeout(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                           long elapsedNanos) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.elapsedNanos = elapsedNanos;
    }

    /** @return 已耗用纳秒 */
    public long getElapsedNanos() { return elapsedNanos; }
}
