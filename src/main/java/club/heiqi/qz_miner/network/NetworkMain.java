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
        network.registerMessage(
                PacketChainModeSwitch.Handler.class,
                PacketChainModeSwitch.class,
                packetId++,
                Side.SERVER);
        network.registerMessage(
                PacketChainSubModeSwitch.Handler.class,
                PacketChainSubModeSwitch.class,
                packetId++,
                Side.SERVER);
        network.registerMessage(
                PacketChainConfigRequest.Handler.class,
                PacketChainConfigRequest.class,
                packetId++,
                Side.SERVER);
        network.registerMessage(
                PacketLootGamesMinesweeperPreviewRequest.Handler.class,
                PacketLootGamesMinesweeperPreviewRequest.class,
                packetId++,
                Side.SERVER);
        network.registerMessage(
                PacketLootGamesMinesweeperPreviewResponse.Handler.class,
                PacketLootGamesMinesweeperPreviewResponse.class,
                packetId++,
                Side.CLIENT);
        // 阶段6：连锁阶段快照下发（服务端 ChainStateProjectionBridge → 客户端投影容器）
        network.registerMessage(
                PacketChainPhaseSnapshot.Handler.class,
                PacketChainPhaseSnapshot.class,
                packetId++,
                Side.CLIENT);
        // 阶段8 块3 F3-a：连锁配置同步下发（服务端 ChainConfigProjectionBridge → 客户端 ChainClientState 三字段）
        network.registerMessage(
                PacketChainConfigSync.Handler.class,
                PacketChainConfigSync.class,
                packetId++,
                Side.CLIENT);
    }
}
