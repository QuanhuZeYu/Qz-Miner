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
 * 将玩家生命周期事件转换为连锁清理事件，在服务端主线程统一收口。
 *
 * <p>LOGOUT 删除状态机槽，重生、切维度与克隆保留代际；这些事件使用 forced 清理玩家当前任务。
 * 状态机负责 phase 与槽位，执行桥负责上下文和执行窗口，ChainStateService 负责玩家态与掉落。
 * 正常执行完成及 watchdog 各自发布所属收尾事件。</p>
 */
public class ChainLifecycleBridge {

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;

    /**
     * 订阅玩家生命周期；与其他连锁桥共享事件总线。
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
     * 抽取玩家 UUID 与原因，交由纯值生命周期入口处理。
     *
     * @param event 玩家状态变更事件
     */
    private void onPlayerState(PlayerStateEvent event) {
        handlePlayerLifecycle(event.player.getUniqueID(), event.reason);
    }

    /**
     * 发布强制生命周期清理；LOGIN 保留刚建立的玩家状态。
     *
     * @param uuid    玩家 UUID
     * @param reason  状态变更原因
     */
    void handlePlayerLifecycle(UUID uuid, PlayerStateEvent.Reason reason) {
        boolean removeSlot;
        String reasonText;
        switch (reason) {
            case LOGIN:
                // LOGIN 保留新建的玩家状态。
                return;
            case LOGOUT:
                // 登出释放玩家槽。
                removeSlot = true;
                reasonText = "player-logout";
                break;
            case RESPAWN:
                // 重生保留代际。
                removeSlot = false;
                reasonText = "player-respawn";
                break;
            case DIMENSION_CHANGE:
                // 维度切换保留代际。
                removeSlot = false;
                reasonText = "player-dimension-change";
                break;
            case CLONE:
                // 克隆保留代际。
                removeSlot = false;
                reasonText = "player-clone";
                break;
            default:
                // 未知原因记录诊断并结束处理。
                MyMod.LOG.debug("[ChainLifecycleBridge] unknown PlayerStateEvent reason={}, skip", reason);
                return;
        }
        long tick = ChainTickSource.currentServerTick();
        long nanos = ChainTickSource.nowNanos();
        // forced 按玩家清理当前任务，generation 使用占位值。
        bus.publish(new LifecycleCleanup(uuid, 0, tick, nanos, reasonText, true, removeSlot));
        MyMod.LOG.debug("[ChainLifecycleBridge] publish LifecycleCleanup player={} reason={} removeSlot={}",
                uuid, reasonText, Boolean.valueOf(removeSlot));
    }
}
