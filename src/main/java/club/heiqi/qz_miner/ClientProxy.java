package club.heiqi.qz_miner;

import club.heiqi.qz_miner.chain.client.ChainPreviewController;
import club.heiqi.qz_miner.client.HudOverlay;
import club.heiqi.qz_miner.client.KeyListener;
import cpw.mods.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    public static ChainPreviewController chainPreviewController;

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        chainPreviewController = new ChainPreviewController();
        chainPreviewController.register();
        HudOverlay hudOverlay = new HudOverlay();
        hudOverlay.register();
        new KeyListener(hudOverlay).register();
    }
}
