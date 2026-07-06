package club.heiqi.qz_miner.chain.lifecycle;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.LifecycleCleanup;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;

/**
 * 连锁生命周期桥：平行订阅 {@link PlayerStateEvent} 转 {@link LifecycleCleanup}（守 NORTH_STAR 不变量 I7）。
 *
 * <h3>三路回 IDLE 中的角色（阶段7 收口）</h3>
 * <p>本类是<b>生命周期强制清理路径</b>，与执行完成快速路径（{@code ChainExecutionEventBridge}）、
 * 看门狗异常兜底（{@code ChainWatchdog}）三路并存：</p>
 * <ul>
 *   <li>玩家登出/重生/切维度 → 本桥 publish LifecycleCleanup(forced=true) → 状态机 handler 豁免 genCheck 强制回 IDLE。</li>
 *   <li>正常连锁完成走执行桥快速路径（forced=false 走 genCheck）。</li>
 *   <li>异常卡死走看门狗兜底（N tick 无推进）。</li>
 * </ul>
 *
 * <h3>影子并行边界（阶段7 不破坏旧链路）</h3>
 * <p>本桥与 {@code ChainStateService.onPlayerStateChanged} 各自独立消费同一 {@link PlayerStateEvent}
 * （同一 {@code ServerMainThreadDispatcher} 主线程收口）。{@code ChainStateService} 全部保留不动
 * （管玩家态、掉落聚合），本桥只 publish LifecycleCleanup 供新链路状态机收口 slots。
 * 阶段8 块2 起新链路真实破坏桥（{@code ChainExecutionEventBridge}）驱动实际破坏，
 * 旧 {@code ChainExecutor} 已于块1 删除。</p>
 *
 * <h3>奠基事实2（生命周期源现成）</h3>
 * <p>{@code ChainStateService.onPlayerStateChanged} 通过 {@link PlayerStateEvent}（5 类 reason：
 * LOGIN/LOGOUT/RESPAWN/DIMENSION_CHANGE/CLONE）收口。本桥平行订阅同一事件源即可，
 * 订阅模式见 {@code ChainStateService:31} / {@code MyMod:138} 的 {@code QzEvents.register}。</p>
 *
 * <h3>字段填充裁决（F.1 W1 + F.2 S1）</h3>
 * <ul>
 *   <li><b>forced=true</b>（F.1 W1）：所有生命周期 reason 都豁免 genCheck（守 I7：玩家都登出了，哪一代都得清）。</li>
 *   <li><b>generation=0</b>：forced 豁免 genCheck，gen 填占位值 0 不影响（被豁免不校验）。</li>
 *   <li><b>removeSlot</b>（F.2 S1）：LOGOUT=true（删槽防泄漏），RESPAWN/DIMENSION_CHANGE/CLONE=false（保 gen 单调）。</li>
 *   <li><b>LOGIN</b>：不 publish（无需清理，玩家刚加入无活跃连锁）。</li>
 * </ul>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本类只 publish LifecycleCleanup，不切 phase、不碰 worker、不写世界。</li>
 *   <li><b>I7</b>：复用 {@link PlayerStateEvent} 现成生命周期源（同一主线程收口），
 *       slots.remove 在状态机 handler 内（唯一写权威）。</li>
 *   <li><b>I10</b>：本类不写 slots，状态机是唯一写权威。</li>
 * </ul>
 */
public class ChainLifecycleBridge {

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;

    /**
     * 构造桥并订阅 {@link PlayerStateEvent}（参考 {@code ChainStateService:31} 的 {@code QzEvents.register} 模式）。
     *
     * <p>构造器纯净可测：只调 {@code QzEvents.register}（操作全局事件总线），
     * 不触碰 {@code FMLCommonHandler.instance()}。bootstrap 是空操作（订阅在构造期已完成），
     * 保留方法仅为与 {@code ChainExecutionEventBridge}/{@code ChainWatchdog} 接线模式对称。</p>
     *
     * @param bus 连锁事件总线
     */
    public ChainLifecycleBridge(ChainEventBus bus) {
        this.bus = bus;
        QzEvents.register(PlayerStateEvent.class, (EventListener<PlayerStateEvent>) this::onPlayerState);
    }

    /**
     * bootstrap 占位方法（订阅已在构造期完成）。
     *
     * <p>保留仅为与 {@code ChainExecutionEventBridge#bootstrap}/{@code ChainWatchdog#bootstrap}
     * 接线模式对称，便于 {@link MyMod#init} 统一调用。</p>
     */
    public void bootstrap() {
        // 订阅已在构造期完成，此处为空操作（模式对称）
    }

    /**
     * 玩家状态变更订阅者：从事件抽取 UUID + reason，委托 {@link #handlePlayerLifecycle} 处理。
     *
     * <p>本方法仅做"事件解包"（uuid 解析 + 转发），可测逻辑在 {@link #handlePlayerLifecycle}，
     * 单测无需 mock {@link EntityPlayer}（避免实例化真实玩家类触发 GL/世界装配，守传感层 §2.2）。</p>
     *
     * @param event 玩家状态变更事件
     */
    private void onPlayerState(PlayerStateEvent event) {
        handlePlayerLifecycle(event.player.getUniqueID(), event.reason);
    }

    /**
     * 处理玩家生命周期：按 reason 转 {@link LifecycleCleanup} publish 到连锁事件总线（可测接缝）。
     *
     * <p>分支：</p>
     * <ul>
     *   <li>{@code LOGIN} → 不 publish（玩家刚加入，无活跃连锁需清理）。</li>
     *   <li>{@code LOGOUT} → LifecycleCleanup(reason="player-logout", forced=true, removeSlot=true)。</li>
     *   <li>{@code RESPAWN} → LifecycleCleanup(reason="player-respawn", forced=true, removeSlot=false)。</li>
     *   <li>{@code DIMENSION_CHANGE} → LifecycleCleanup(reason="player-dimension-change", forced=true, removeSlot=false)。</li>
     *   <li>{@code CLONE} → LifecycleCleanup(reason="player-clone", forced=true, removeSlot=false)。</li>
     * </ul>
     *
     * @param uuid    玩家 UUID
     * @param reason  状态变更原因
     */
    void handlePlayerLifecycle(UUID uuid, PlayerStateEvent.Reason reason) {
        boolean removeSlot;
        String reasonText;
        switch (reason) {
            case LOGIN:
                // 玩家刚加入，无活跃连锁需清理
                return;
            case LOGOUT:
                // F.2 S1：LOGOUT 删槽防泄漏
                removeSlot = true;
                reasonText = "player-logout";
                break;
            case RESPAWN:
                // F.2 S1：重生保 gen 单调
                removeSlot = false;
                reasonText = "player-respawn";
                break;
            case DIMENSION_CHANGE:
                // F.2 S1：维度切换保 gen 单调
                removeSlot = false;
                reasonText = "player-dimension-change";
                break;
            case CLONE:
                // F.2 S1：克隆保 gen 单调
                removeSlot = false;
                reasonText = "player-clone";
                break;
            default:
                // 未知 reason 不 publish（防御性）
                MyMod.LOG.debug("[ChainLifecycleBridge] unknown PlayerStateEvent reason={}, skip", reason);
                return;
        }
        long tick = ChainTickSource.currentServerTick();
        long nanos = ChainTickSource.nowNanos();
        // F.1 W1：forced=true 豁免 genCheck（守 I7）；gen=0 占位（被豁免不校验）
        bus.publish(new LifecycleCleanup(uuid, 0, tick, nanos, reasonText, true, removeSlot));
        MyMod.LOG.debug("[ChainLifecycleBridge] publish LifecycleCleanup player={} reason={} removeSlot={}",
                uuid, reasonText, Boolean.valueOf(removeSlot));
    }
}
