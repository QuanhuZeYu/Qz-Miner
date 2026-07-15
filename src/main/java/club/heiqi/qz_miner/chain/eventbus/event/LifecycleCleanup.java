package club.heiqi.qz_miner.chain.eventbus.event;

import java.util.UUID;

import club.heiqi.qz_miner.chain.eventbus.ChainEvent;

/**
 * 玩家连锁状态清理事件（对应 cleanupPlayerState 语义，守 NORTH_STAR 不变量 I7）。
 *
 * <h3>阶段7 三路回 IDLE 裁决（F.1 W1 + F.2 S1）</h3>
 * <p>阶段7 起 LifecycleCleanup 携带两个字段区分两类来源：</p>
 * <ul>
 *   <li><b>forced</b>（F.1 W1）：生命周期强制清理豁免 genCheck。
 *       <ul>
 *         <li>{@code forced=true}：玩家登出/重生/切维度（{@code ChainLifecycleBridge} 平行订阅
 *             {@code PlayerStateEvent} 转发），状态机 handler 跳过 genCheck 强制回 IDLE。
 *             守 I7：玩家都登出了，哪一代都得清；跨包拿不到 slot.generation，强制清理不该受代际约束。</li>
 *         <li>{@code forced=false}：执行完成快速收尾路径（{@code ChainExecutionEventBridge} 队列空时
 *             同 tick 紧接 publish），走 genCheck（gen 已知，代际匹配校验）。</li>
 *       </ul>
 *   </li>
 *   <li><b>removeSlot</b>（F.2 S1）：转移完成后是否删 {@code slots} 槽。
 *       <ul>
 *         <li>{@code removeSlot=true}：LOGOUT（玩家登出）删槽防泄漏。</li>
 *         <li>{@code removeSlot=false}：RESPAWN/DIMENSION_CHANGE/CLONE（保 gen 单调）与
 *             执行完成路径（玩家在线，保槽）。</li>
 *       </ul>
 *   </li>
 * </ul>
 */
public final class LifecycleCleanup extends ChainEvent {

    /** 清理原因（自由文本，用于诊断）。 */
    private final String reason;
    /** F.1 W1：是否豁免 genCheck 强制回 IDLE（生命周期清理=true，执行完成=false）。 */
    private final boolean forced;
    /** F.2 S1：转移后是否删 slots 槽（LOGOUT=true 删槽防泄漏；RESPAWN/维度切换/执行完成=false 保 gen 单调）。 */
    private final boolean removeSlot;

    /**
     * 全参构造器（阶段7 三路回 IDLE 裁决落地）。
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
     * <p>保留以减少阶段5 现有调用点与单测的改动面（默认走 genCheck、不删槽，
     * 语义等同阶段7 前的执行完成快速路径）。</p>
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

    /** @return 是否豁免 genCheck 强制回 IDLE（F.1 W1，生命周期清理=true） */
    public boolean isForced() { return forced; }

    /** @return 转移后是否删 slots 槽（F.2 S1，LOGOUT=true；RESPAWN/维度切换/执行完成=false） */
    public boolean isRemoveSlot() { return removeSlot; }
}
