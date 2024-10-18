package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.MOD_INFO;
import com.gtnewhorizon.gtnhlib.blockpos.BlockPos;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;

public class Qz_MinerSimpleNetwork {
    public static final SimpleNetworkWrapper networkWrapper = NetworkRegistry.INSTANCE.newSimpleChannel(MOD_INFO.NETWORK_CHANNEL);

    public Qz_MinerSimpleNetwork() {
        networkWrapper.registerMessage(PacketChangeRangeMode.Handler.class, PacketChangeRangeMode.class, 0, Side.SERVER);
        networkWrapper.registerMessage(PacketIsHold.Handler.class, PacketIsHold.class, 1, Side.SERVER);
        networkWrapper.registerMessage(PacketChangeMainMode.Handler.class, PacketChangeMainMode.class, 2, Side.SERVER);
    }

    public void sendMessageToDim(IMessage message, int Dim) {
        networkWrapper.sendToDimension(message, Dim);
    }

    /**
     * 向维度某个点发包(服务器到客户端)
     * @param msg
     * @param dim
     * @param pos
     */
    public static void sendMessageAroundPos(IMessage msg, int dim, BlockPos pos) {
        // TargetPoint的构造器为：
        // 维度id x坐标 y坐标 z坐标 覆盖范围
        // 其中，覆盖范围指接受此更新数据包的坐标的范围
        networkWrapper.sendToAllAround(msg, new NetworkRegistry.TargetPoint(dim, pos.getX(), pos.getY(), pos.getZ(), 2.0D));
    }

    /** 向某个玩家发包（服务器到客户端）
     * @param msg
     * @param player
     */
    public static void sendMessageToPlayer(IMessage msg, EntityPlayerMP player) {
        networkWrapper.sendTo(msg, player);
    }

    /** 向所有人发包（服务器到客户端）
     * @param msg
     */
    public static void sendMessageToAll(IMessage msg) {
        networkWrapper.sendToAll(msg);
    }

    /** 向服务器发包（客户端到服务器）
     * @param msg
     */
    public static void sendMessageToServer(IMessage msg) {
//        MY_LOG.LOG.info("向服务器发包: {}", msg.toString());
        networkWrapper.sendToServer(msg);
    }
}
