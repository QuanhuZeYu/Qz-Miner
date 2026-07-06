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

    /**
     * 服务端处理通用子模式切换。
     */
    public static class Handler implements IMessageHandler<PacketChainSubModeSwitch, IMessage> {

        @Override
        public IMessage onMessage(PacketChainSubModeSwitch message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            final int subModeOrdinal = message.subModeOrdinal;
            ServerMainThreadDispatcher.run(() -> {
                if (MyMod.chainStateService == null || player == null) {
                    return;
                }

                ChainSubMode[] subModes = ChainSubMode.values();
                ChainSubMode subMode = subModeOrdinal >= 0 && subModeOrdinal < subModes.length
                    ? subModes[subModeOrdinal]
                    : null;

                MyMod.chainStateService.setPlayerSelectedSubMode(player.getUniqueID(), subMode);
                // 守 I4：publish 在 ServerMainThreadDispatcher.run lambda 内（line 49），已收口主线程
                // 阶段3影子并行：保留旧 setPlayerSelectedSubMode，新链路仅推进状态机观测
                // 输入事件 generation 传 0 豁免代际判定
                if (MyMod.chainEventBus != null) {
                    ChainPlayerState state = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
                    ChainMode newMode = state.getSelectedMode();
                    ChainSubMode newSubMode = state.getSelectedSubMode();
                    // 子模式刚被 set，但若当前主模式不含子变体，newSubMode 可能为 null；防御性兜底
                    if (newSubMode == null) {
                        newSubMode = ChainSubMode.CHAIN_BASE;
                    }
                    MyMod.chainEventBus.publish(new ModeSwitched(
                            player.getUniqueID(), 0,
                            ChainTickSource.currentServerTick(), ChainTickSource.nowNanos(),
                            newMode, newSubMode));
                }
            });
            return null;
        }
    }
}
