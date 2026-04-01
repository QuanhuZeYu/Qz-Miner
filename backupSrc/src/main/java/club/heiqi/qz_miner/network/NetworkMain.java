package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.Constant;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class NetworkMain {
    public final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(Constant.MODID);
    public int packetID = 0;
    public void registry() {
        // 注册按键检测
        network.registerMessage(PacketChainSwitcher.ChainSwitcherPacketHandler.class, PacketChainSwitcher.class, packetID++, Side.SERVER);
        // 注册配置网络
        network.registerMessage(PacketMinerConfig.PacketMinerConfigHandler.class, PacketMinerConfig.class, packetID++, Side.SERVER);
        network.registerMessage(PacketMinerConfig.PacketMinerConfigHandler.class, PacketMinerConfig.class, packetID++, Side.CLIENT);
        // 注册连锁模式网络
        network.registerMessage(PacketMinerModeState.PacketMinerModeStateHandler.class, PacketMinerModeState.class, packetID++, Side.SERVER);
    }
}
