package club.heiqi.qz_miner.chain.eventbus;

import java.util.UUID;

/**
 * 连锁框架事件基类。
 *
 * <p>不可变事件，构造后所有字段不可再修改，可安全跨线程传递（守 NORTH_STAR 不变量 I1/I4）。
 * 子类自有字段也必须全部 {@code final}。</p>
 *
 * <ul>
 *   <li>{@code generation}：所属连锁代际，用于阶段 2 状态机做代际陈旧判定</li>
 *   <li>{@code serverTick}：发布时服务端 tick 计数</li>
 *   <li>{@code playerUUID}：触发玩家 UUID</li>
 *   <li>{@code timestampNanos}：发布时刻 {@link System#nanoTime()}，仅供诊断/时序分析</li>
 * </ul>
 */
public abstract class ChainEvent {

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
        this.playerUUID = playerUUID;
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
