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
 * 登录及规划完成时下发玩家已接受的配置、隧道方向来源与匹配数。
 * 匹配数取完成事件的确认目标数，登录时取零；半径和数量优先取玩家已接受值，再回退 Config。
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

    /** 主线程使用规划确认目标数刷新配置投影。 */
    private void onPlanCompleted(PlanCompleted event) {
        sendAcceptedConfig(event.getPlayerUUID(), event.getTotalTargets());
    }

    /** 登录时下发已接受配置，并以零初始化匹配数。 */
    private void onPlayerStateChanged(PlayerStateEvent event) {
        if (event.reason != PlayerStateEvent.Reason.LOGIN) {
            return;
        }
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
