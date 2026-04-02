package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MyMod;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 客户端按键状态同步包。
 *
 * 客户端长按/松开按键时发送此包到服务端，
 * 服务端据此更新对应玩家的状态。
 */
public class PacketKeyState implements IMessage {

    /**
     * 按键是否处于按下状态。
     */
    public boolean pressed;

    /**
     * 按键标识符。
     */
    public int keyId;

    public PacketKeyState() {
    }

    public PacketKeyState(int keyId, boolean pressed) {
        this.keyId = keyId;
        this.pressed = pressed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.keyId = buf.readInt();
        this.pressed = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.keyId);
        buf.writeBoolean(this.pressed);
    }

    /**
     * 服务端处理器。
     */
    public static class KeyStatePacketHandler implements IMessageHandler<PacketKeyState, IMessage> {

        @Override
        public IMessage onMessage(PacketKeyState message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            MyMod.LOG.debug("[Network] Player {} key state: keyId={}, pressed={}",
                    player.getCommandSenderName(), message.keyId, message.pressed);
            return null;
        }
    }
}