package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.core.Manager;
import club.heiqi.qz_miner.core.MinerConfig;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;

public class PacketMinerConfig implements IMessage {
    public MinerConfig minerConfig = new MinerConfig();

    public PacketMinerConfig() {}
    public PacketMinerConfig(MinerConfig minerConfig) {
        this.minerConfig = minerConfig;
    }

    public void fromBytes(ByteBuf buf) {
        minerConfig.bigRadius = buf.readInt();
        minerConfig.blockLimit = buf.readInt();
        minerConfig.smallRadius = buf.readInt();
    }

    public void toBytes(ByteBuf buf) {
        buf.writeInt(minerConfig.bigRadius);
        buf.writeInt(minerConfig.blockLimit);
        buf.writeInt(minerConfig.smallRadius);
    }

    public static class PacketMinerConfigHandler implements IMessageHandler<PacketMinerConfig, IMessage> {
        public IMessage onMessage(PacketMinerConfig message, MessageContext ctx) {
            // 如果处理该消息的是服务端
            if (ctx.side.isServer()) {
                EntityPlayerMP playerMP = ctx.getServerHandler().playerEntity;
                Manager manager = MyMod.playerManager.managers.get(playerMP);

                // 服务端校验传来的配置
                manager.receiveClientConfig(message.minerConfig);

                // 返回服务端校验后的值
                return new PacketMinerConfig(manager.pConfig);
            }

            if (ctx.side.isClient()) {
                MinerConfig minerConfig = message.minerConfig;
                Config.bigRadius = minerConfig.bigRadius;
                Config.blockLimit = minerConfig.blockLimit;
                Config.smallRadius = minerConfig.smallRadius;
            }
            return null;
        }
    }
}
