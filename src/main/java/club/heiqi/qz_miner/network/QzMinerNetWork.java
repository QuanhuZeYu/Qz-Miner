package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MOD_INFO;
import com.glodblock.github.util.BlockPos;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

public class QzMinerNetWork {
    public static final SimpleNetworkWrapper networkWrapper = new SimpleNetworkWrapper(MOD_INFO.NETWORK_CHANNEL);

    public QzMinerNetWork() {
        networkWrapper.registerMessage(PacketMainMode.Handler.class, PacketMainMode.class, 0, Side.SERVER);
        networkWrapper.registerMessage(PacketRangeMode.Handler.class, PacketRangeMode.class, 1, Side.SERVER);
        networkWrapper.registerMessage(PacketChainMode.Handler.class, PacketChainMode.class, 2, Side.SERVER);
        networkWrapper.registerMessage(PacketIsReady.Handler.class, PacketIsReady.class, 3, Side.SERVER);
        networkWrapper.registerMessage(PacketPrintResult.Handler.class, PacketPrintResult.class, 4, Side.SERVER);

        networkWrapper.registerMessage(PacketIsRunning.Handler.class, PacketIsRunning.class, 5, Side.CLIENT);
        networkWrapper.registerMessage(PacketSweepMine.Handler.class, PacketSweepMine.class, 6, Side.CLIENT);
    }

    public static void sendMessageToDim(IMessage message, int dim) {
        networkWrapper.sendToDimension(message, dim);
    }

    public static void sendMessageAroundPos(IMessage imessage, int dim, BlockPos pos, float range) {
        networkWrapper.sendToAllAround(
            imessage,
            new cpw.mods.fml.common.network.NetworkRegistry.TargetPoint(dim, pos.getX(), pos.getY(), pos.getZ(), range));
    }

    public static void sendMessageToPlayer(IMessage message, EntityPlayerMP player) {
        networkWrapper.sendTo(message, player);
    }

    public static void sendMessageToAll(IMessage message) {
        networkWrapper.sendToAll(message);
    }

    public static void sendMessageToServer(IMessage message) {
        networkWrapper.sendToServer(message);
    }
}
