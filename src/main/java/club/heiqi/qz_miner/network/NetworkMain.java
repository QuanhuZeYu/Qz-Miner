package club.heiqi.qz_miner.network;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * 网络通信主类。
 *
 * 管理所有网络包的注册和发送。
 */
public final class NetworkMain {

    /**
     * SimpleNetworkWrapper 实例，用于发送和接收网络包。
     */
    public final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel("qz_miner");

    /**
     * 自增的包 ID。
     */
    private int packetId = 0;

    /**
     * 注册所有网络包。
     */
    public void register() {
        network.registerMessage(
                PacketKeyState.KeyStatePacketHandler.class,
                PacketKeyState.class,
                packetId++,
                Side.SERVER);
    }
}