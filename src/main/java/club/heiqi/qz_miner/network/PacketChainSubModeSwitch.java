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
 * 通用连锁子模式切换同步包。
 */
public class PacketChainSubModeSwitch implements IMessage {

    public int subModeOrdinal;

    public PacketChainSubModeSwitch() {}

    /**
     * 使用目标子模式创建同步包。
     *
     * @param subMode 目标子模式
     */
    public PacketChainSubModeSwitch(ChainSubMode subMode) {
        this.subModeOrdinal = subMode == null ? -1 : subMode.ordinal();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        subModeOrdinal = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(subModeOrdinal);
    }

    /** 将合法 wire ordinal 映射为子模式，越界返回 null 交主线程拒绝。 */
    static ChainSubMode resolveSubMode(int ordinal) {
        ChainSubMode[] subModes = ChainSubMode.values();
        return ordinal >= 0 && ordinal < subModes.length ? subModes[ordinal] : null;
    }

    /**
     * 服务端处理通用子模式切换。
     */
    public static class Handler implements IMessageHandler<PacketChainSubModeSwitch, IMessage> {

        @Override
        public IMessage onMessage(PacketChainSubModeSwitch message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            final UUID playerId = player.getUniqueID();
            final WeakReference<EntityPlayerMP> endpoint = new WeakReference<EntityPlayerMP>(player);
            final int subModeOrdinal = message.subModeOrdinal;
            ServerMainThreadDispatcher.run(() -> {
                EntityPlayerMP captured = endpoint.get();
                EntityPlayer current = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerId);
                if (MyMod.chainStateService == null || captured == null || current != captured) {
                    return;
                }

                ChainSubMode subMode = resolveSubMode(subModeOrdinal);

                ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(playerId);
                ChainSubMode previousSubMode = state.getSelectedSubMode();
                MyMod.chainStateService.setPlayerSelectedSubMode(playerId, subMode);
                if (state.getSelectedSubMode() == previousSubMode) {
                    return;
                }
                // 主线程更新玩家子模式并发布观测，输入代际由状态机按当前阶段处理。
                if (MyMod.chainEventBus != null) {
                    ChainMode newMode = state.getSelectedMode();
                    ChainSubMode newSubMode = state.getSelectedSubMode();
                    // 主模式缺少子变体时，用 CHAIN_BASE 形成完整观测。
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
