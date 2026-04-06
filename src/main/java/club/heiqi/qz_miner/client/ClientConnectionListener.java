package club.heiqi.qz_miner.client;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.network.PacketChainConfigRequest;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端连接事件监听。
 */
@SideOnly(Side.CLIENT)
public class ClientConnectionListener {

    public void register() {
        cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        if (MyMod.chainStateService == null) {
            return;
        }

        MyMod.chainStateService.setClientRequestedChainConfig(Config.chainRadius, Config.chainMaxBlocks);
        if (MyMod.networkMain == null || FMLClientHandler.instance().getClient().isSingleplayer()) {
            return;
        }

        MyMod.networkMain.network.sendToServer(new PacketChainConfigRequest(Config.chainRadius, Config.chainMaxBlocks));
    }
}
