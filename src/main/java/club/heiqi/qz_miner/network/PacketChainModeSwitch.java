package club.heiqi.qz_miner.network;

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
            final int modeOrdinal = message.modeOrdinal;
            ServerMainThreadDispatcher.run(() -> {
                if (MyMod.chainStateService == null || player == null) {
                    return;
                }

                ChainMode[] modes = ChainMode.values();
                ChainMode mode = modeOrdinal >= 0 && modeOrdinal < modes.length
                    ? modes[modeOrdinal]
                    : ChainMode.CHAIN;

                MyMod.chainStateService.setPlayerSelectedMode(player.getUniqueID(), mode);
                // 守 I4：publish 在 ServerMainThreadDispatcher.run lambda 内（line 41），已收口主线程
                // 阶段3影子并行：保留旧 setPlayerSelectedMode，新链路仅推进状态机观测
                // 输入事件 generation 传 0 豁免代际判定
                if (MyMod.chainEventBus != null) {
                    ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
                    ChainMode newMode = state.getSelectedMode();
                    ChainSubMode newSubMode = state.getSelectedSubMode();
                    // state 解析出的子模式理论上非 null（registry 已 resolve），但防御性兜底避免下游 NPE
                    if (newSubMode == null) {
                        newSubMode = ChainSubMode.CHAIN_BASE;
                    }
                    long serverRoundId = MyMod.autoToolSwapRoundService == null ? 0L
                            : MyMod.autoToolSwapRoundService.currentRoundId(player.getUniqueID(), player);
                    MyMod.chainEventBus.publish(new ModeSwitched(
                            player.getUniqueID(), serverRoundId, 0,
                            ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                            newMode, newSubMode));
                }
            });
            return null;
        }
    }
}
