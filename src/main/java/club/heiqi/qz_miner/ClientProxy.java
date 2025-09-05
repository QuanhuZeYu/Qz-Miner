package club.heiqi.qz_miner;

import club.heiqi.qz_miner.client.KeyListener;
import club.heiqi.qz_miner.client.MinerRenderer;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {
    public KeyListener   keyListener   = new KeyListener();
    public MinerRenderer minerRenderer = new MinerRenderer();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        keyListener.registry();
        minerRenderer.registry();
    }

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.

}
