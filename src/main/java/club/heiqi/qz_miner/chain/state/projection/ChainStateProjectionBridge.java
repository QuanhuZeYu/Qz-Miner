package club.heiqi.qz_miner.chain.state.projection;

import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainEventBus;
import club.heiqi.qz_miner.chain.eventbus.event.ChainPhaseChanged;
import club.heiqi.qz_miner.network.PacketChainPhaseSnapshot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/** 主线程订阅状态机阶段广播，向对应玩家下发客户端阶段投影。 */
public class ChainStateProjectionBridge {

    /** 注入的事件总线（与状态机共享同一实例）。 */
    private final ChainEventBus bus;

    /**
     * 构造桥并订阅 {@link ChainPhaseChanged}（构造期订阅生效，无需单独 bootstrap）。
     *
     * @param bus 事件总线
     */
    public ChainStateProjectionBridge(ChainEventBus bus) {
        this.bus = bus;
        bus.subscribe(ChainPhaseChanged.class, this::onPhaseChanged);
    }

    /** 解析当前服务端玩家，将已完成转移的阶段、代际与 tick 下发为快照。 */
    private void onPhaseChanged(ChainPhaseChanged event) {
        UUID playerUUID = event.getPlayerUUID();
        if (MyMod.networkMain == null || MyMod.playerManager == null) {
            return;
        }
        EntityPlayer player = MyMod.playerManager.getPlayer(playerUUID);
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        PacketChainPhaseSnapshot packet = new PacketChainPhaseSnapshot(
                event.getToPhaseOrdinal(),
                event.getGeneration(),
                event.getServerTick());
        MyMod.networkMain.network.sendTo(packet, (EntityPlayerMP) player);
    }
}
