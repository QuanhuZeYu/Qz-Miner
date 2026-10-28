package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 连锁收尾事件：forced 按玩家收尾；普通完成由状态机按代际筛选，执行桥按代际与执行轮清理上下文。
 * removeSlot 在登出时释放玩家槽，其余路径保留代际。
 */
public final class LifecycleCleanup extends ChainEvent {

    /** 清理原因（自由文本，用于诊断）。 */
    private final String reason;
    /** 是否按玩家强制收尾；生命周期清理为 true，执行完成为 false。 */
    private final boolean forced;
    /** 是否释放玩家槽；登出取 true，其余收尾保留代际。 */
    private final boolean removeSlot;

    /**
     * 构造兼容缺省轮次、带清理策略的事件。
     *
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际（forced=true 时被豁免，可填占位值 0）
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param reason         清理原因
     * @param forced         是否豁免 genCheck（生命周期清理=true；执行完成=false）
     * @param removeSlot     转移后是否删 slots 槽（LOGOUT=true；RESPAWN/维度切换/执行完成=false）
     */
    public LifecycleCleanup(UUID playerUUID, int generation, long serverTick, long timestampNanos,
                             String reason, boolean forced, boolean removeSlot) {
        this(playerUUID, ChainEvent.NO_SERVER_ROUND_ID, generation, serverTick, timestampNanos,
                reason, forced, removeSlot);
    }

    /** 构造带服务端轮次关联的生命周期清理事件。 */
    public LifecycleCleanup(UUID playerUUID, long serverRoundId, int generation, long serverTick, long timestampNanos,
                            String reason, boolean forced, boolean removeSlot) {
        super(playerUUID, serverRoundId, generation, serverTick, timestampNanos);
        this.reason = reason;
        this.forced = forced;
        this.removeSlot = removeSlot;
    }

    /**
     * 兼容构造器（forced=false + removeSlot=false）。
     *
     * <p>默认按代际校验并保留玩家槽，供普通执行完成收尾。</p>
     *
     * @param playerUUID     触发玩家
     * @param generation     所属连锁代际
     * @param serverTick     发布时服务端 tick
     * @param timestampNanos 发布时刻纳秒戳
     * @param reason         清理原因
     */
    public LifecycleCleanup(UUID playerUUID, int generation, long serverTick, long timestampNanos, String reason) {
        this(playerUUID, generation, serverTick, timestampNanos, reason, false, false);
    }

    /** @return 清理原因 */
    public String getReason() { return reason; }

    /** @return 是否豁免 genCheck 强制回 IDLE（生命周期清理=true） */
    public boolean isForced() { return forced; }

    /** @return 转移后是否删 slots 槽（LOGOUT=true；RESPAWN/维度切换/执行完成=false） */
    public boolean isRemoveSlot() { return removeSlot; }
}
