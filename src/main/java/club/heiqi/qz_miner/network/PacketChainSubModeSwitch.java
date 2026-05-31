package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainSubMode;
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
            });
            return null;
        }
    }
}
