package club.heiqi.qz_miner.chain.eventbus;

import java.util.UUID;

/**
 * 连锁事件的不可变身份与诊断时间戳，供跨线程传递。
 * 子类同样使用 final 字段；generation 供状态机筛选代际，serverRoundId 关联所属执行轮。
 */
public abstract class ChainEvent {

    /** 兼容旧调用方的未关联服务端轮次标记。 */
    public static final long NO_SERVER_ROUND_ID = 0L;

    /** 服务端分配的不可变连锁轮次关联。 */
    private final long serverRoundId;
    /** 所属连锁代际。 */
    private final int generation;
    /** 发布时服务端 tick 计数。 */
    private final long serverTick;
    /** 触发玩家 UUID。 */
    private final UUID playerUUID;
    /** 发布时刻纳秒戳。 */
    private final long timestampNanos;

    /**
     * 构造事件。
     *
     * @param playerUUID     触发玩家 UUID
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     */
    protected ChainEvent(UUID playerUUID, int generation, long serverTick, long timestampNanos) {
        this(playerUUID, NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos);
    }

    /**
     * 构造带服务端轮次关联的事件。
     *
     * @param playerUUID     触发玩家 UUID
     * @param serverRoundId  服务端分配的不可变轮次 ID
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     */
    protected ChainEvent(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos) {
        this.playerUUID = playerUUID;
        this.serverRoundId = serverRoundId;
        this.generation = generation;
        this.serverTick = serverTick;
        this.timestampNanos = timestampNanos;
    }

    /**
     * @return 所属连锁代际
     */
    public int getGeneration() {
        return generation;
    }

    /**
     * @return 服务端分配的不可变轮次 ID；旧调用方构造的事件返回 {@link #NO_SERVER_ROUND_ID}
     */
    public long getServerRoundId() {
        return serverRoundId;
    }

    /**
     * @return 发布时服务端 tick
     */
    public long getServerTick() {
        return serverTick;
    }

    /**
     * @return 触发玩家 UUID
     */
    public UUID getPlayerUUID() {
        return playerUUID;
    }

    /**
     * @return 发布时刻纳秒戳
     */
    public long getTimestampNanos() {
        return timestampNanos;
    }
}
