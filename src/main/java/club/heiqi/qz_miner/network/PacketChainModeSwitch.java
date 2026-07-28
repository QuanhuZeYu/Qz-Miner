package club.heiqi.qz_miner.network;

import java.lang.ref.WeakReference;
import java.util.UUID;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.eventbus.event.ModeSwitched;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.thread.ServerMainThreadDispatcher;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁模式切换同步包。
 */
public class PacketChainModeSwitch implements IMessage {

    public int modeOrdinal;

    public PacketChainModeSwitch() {}

    public PacketChainModeSwitch(ChainMode mode) {
        this.modeOrdinal = mode.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        modeOrdinal = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(modeOrdinal);
    }

    public static class Handler implements IMessageHandler<PacketChainModeSwitch, IMessage> {

        @Override
        public IMessage onMessage(PacketChainModeSwitch message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            final UUID playerId = player.getUniqueID();
            final WeakReference<EntityPlayerMP> endpoint = new WeakReference<EntityPlayerMP>(player);
            final int modeOrdinal = message.modeOrdinal;
            ServerMainThreadDispatcher.run(() -> {
                EntityPlayerMP captured = endpoint.get();
                EntityPlayer current = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerId);
                if (MyMod.chainStateService == null || captured == null || current != captured) {
                    return;
                }

                ChainMode[] modes = ChainMode.values();
                ChainMode mode = modeOrdinal >= 0 && modeOrdinal < modes.length
                    ? modes[modeOrdinal]
                    : ChainMode.CHAIN;

                ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(playerId);
                ChainMode previousMode = state.getSelectedMode();
                MyMod.chainStateService.setPlayerSelectedMode(playerId, mode);
                if (state.getSelectedMode() == previousMode) {
                    return;
                }
                // 守 I4：publish 在 ServerMainThreadDispatcher.run lambda 内（line 41），已收口主线程
                // 阶段3影子并行：保留旧 setPlayerSelectedMode，新链路仅推进状态机观测
                // 输入事件 generation 传 0 豁免代际判定
                if (MyMod.chainEventBus != null) {
                    ChainMode newMode = state.getSelectedMode();
                    ChainSubMode newSubMode = state.getSelectedSubMode();
                    // state 解析出的子模式理论上非 null（registry 已 resolve），但防御性兜底避免下游 NPE
                    if (newSubMode == null) {
                        newSubMode = ChainSubMode.CHAIN_BASE;
                    }
                    long serverRoundId = MyMod.autoToolSwapRoundService == null ? 0L
                            : MyMod.autoToolSwapRoundService.currentRoundId(playerId, captured);
                    MyMod.chainEventBus.publish(new ModeSwitched(
                            playerId, serverRoundId, 0,
                            ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                            newMode, newSubMode));
                }
            });
            return null;
        }
    }
}
