package club.heiqi.qz_miner.chain.state.projection;

import java.util.UUID;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.PlanCompleted;
import club.heiqi.qz_miner.event.EventListener;
import club.heiqi.qz_miner.event.PlayerStateEvent;
import club.heiqi.qz_miner.event.QzEvents;
import club.heiqi.qz_miner.network.PacketChainConfigSync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 阶段8 块3 F3-a：连锁配置下发桥（服务端订阅者）。
 *
 * <p>订阅两类信号，组装 {@link PacketChainConfigSync} 下发到客户端 {@code ChainClientState} 的
 * {@code serverChainRadius}/{@code serverChainMaxBlocks}/{@code serverMatchedTargetCount} 三字段：</p>
 * <ul>
 *   <li>{@link PlanCompleted}（chainEventBus，主线程 drain 触发）：matchedCount 取
 *       {@code event.getTotalTargets()}（= 规划确认目标数），radius/maxBlocks 取 {@link Config}。</li>
 *   <li>{@link PlayerStateEvent} LOGIN（QzEvents 全局总线）：进服时下发基础 config（matchedCount=0），
 *       确保 HUD 初始有 radius/maxBlocks 显示，不必等首次连锁完成。</li>
 * </ul>
 *
 * <h3>F3-a matchedCount 来源</h3>
 * <p>新链路真值是 {@code PlanCompleted.totalTargets}（= {@code searchContext.getConfirmedCount()}，
 * 由 {@code ChainPlanningEventBridge} worker 完成路径 publish）。本桥订阅 PlanCompleted 取该值下发，
 * 客户端 HUD 据此显示「服务端匹配数」。</p>
 *
 * <h3>守 NORTH_STAR 不变量</h3>
 * <ul>
 *   <li><b>I1</b>：本桥只 {@code sendTo} 下发配置，<b>绝不</b>碰世界、绝不切态、绝不调破坏 API。</li>
 *   <li><b>I4</b>：PlanCompleted 订阅仅在主线程 drain 调用（chainEventBus publish 跨线程、drain 主线程），
 *       {@code sendTo} 是网络层入队操作线程安全；PlayerStateEvent LOGIN 在主线程触发。</li>
 *   <li><b>I7</b>：本桥不持有 per-player 状态，无需生命周期清理（玩家登出时 playerManager 自动摘除）。</li>
 * </ul>
 */
public class ChainConfigProjectionBridge {

    /** 注入的事件总线（与状态机/规划桥共享同一实例）。 */
    private final ChainEventBus bus;

    /**
     * 构造桥并订阅 {@link PlanCompleted}（chainEventBus）+ {@link PlayerStateEvent} LOGIN（QzEvents）。
     *
     * @param bus 事件总线
     */
    public ChainConfigProjectionBridge(ChainEventBus bus) {
        this.bus = bus;
        bus.subscribe(PlanCompleted.class, this::onPlanCompleted);
        QzEvents.register(PlayerStateEvent.class, (EventListener<PlayerStateEvent>) this::onPlayerStateChanged);
    }

    /**
     * 规划完成：取 totalTargets 作为 matchedCount，组装 config 包 sendTo 客户端。
     *
     * <p>契约：仅主线程 drain 调用。守 I1：只 sendTo，不碰世界。</p>
     *
     * @param event 规划完成事件（携带 totalTargets = confirmedCount）
     */
    private void onPlanCompleted(PlanCompleted event) {
        // 守 I1：只读订阅，不碰世界；下面只 sendTo 下发配置
        sendAcceptedConfig(event.getPlayerUUID(), event.getTotalTargets());
    }

    /**
     * 玩家状态变更：LOGIN 时下发基础 config（matchedCount=0）。
     *
     * <p>其他 reason（LOGOUT/RESPAWN/DIMENSION_CHANGE/CLONE）不下发——这些场景由生命周期桥清理服务端态，
     * 客户端 config 字段保留旧值无害（下次连锁 PlanCompleted 会刷新）。</p>
     *
     * @param event 玩家状态事件
     */
    private void onPlayerStateChanged(PlayerStateEvent event) {
        if (event.reason != PlayerStateEvent.Reason.LOGIN) {
            return;
        }
        // LOGIN：下发基础 config（matchedCount=0），radius/maxBlocks 取 Config
        sendAcceptedConfig(event.player.getUniqueID(), 0);
    }

    /**
     * 组装 config 包并 sendTo 指定玩家。
     *
     * @param playerUUID       目标玩家
     * @param matchedTargetCount 已匹配目标数（PlanCompleted 时 = totalTargets；LOGIN 时 = 0）
     */
    public void sendAcceptedConfig(UUID playerUUID, int matchedTargetCount) {
        if (MyMod.networkMain == null || MyMod.playerManager == null) {
            return;
        }
        EntityPlayer player = MyMod.playerManager.getPlayer(playerUUID);
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        club.heiqi.qz_miner.chain.state.ChainPlayerState state = MyMod.chainStateService == null
                ? null : MyMod.chainStateService.getPlayerState(playerUUID);
        int acceptedRadius = state != null && state.getRequestedChainRadius() > 0
                ? state.getRequestedChainRadius() : Config.chainRadius;
        int acceptedMaxBlocks = state != null && state.getRequestedChainMaxBlocks() > 0
                ? state.getRequestedChainMaxBlocks() : Config.chainMaxBlocks;
        club.heiqi.qz_miner.chain.planner.TunnelDirectionSource source = state == null
                ? club.heiqi.qz_miner.chain.planner.TunnelDirectionSource.legacyDefault()
                : state.getAcceptedTunnelDirectionSource();
        PacketChainConfigSync packet = new PacketChainConfigSync(
                acceptedRadius, acceptedMaxBlocks, matchedTargetCount, source);
        MyMod.networkMain.network.sendTo(packet, (EntityPlayerMP) player);
    }
}
