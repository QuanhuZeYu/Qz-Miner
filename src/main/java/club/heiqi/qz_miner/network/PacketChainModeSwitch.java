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
                // 主线程更新玩家选择并发布观测，输入代际由状态机按当前阶段处理。
                if (MyMod.chainEventBus != null) {
                    ChainMode newMode = state.getSelectedMode();
                    ChainSubMode newSubMode = state.getSelectedSubMode();
                    // 缺省子模式使用 CHAIN_BASE 形成完整观测。
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
