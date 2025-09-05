package club.heiqi.qz_miner.network;

import club.heiqi.qz_miner.Constant;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public class NetworkMain {
    public final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(Constant.MODID);
    public int packetID = 0;
    public void registry() {
        network.registerMessage(ChainSwitcherPacket.ChainSwitcherPacketHandler.class, ChainSwitcherPacket.class, packetID++, Side.SERVER);
    }
}
