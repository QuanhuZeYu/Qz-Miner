package club.heiqi.qz_miner;

import club.heiqi.qz_miner.KeyBind.MinerKeyBind;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        MinerKeyBind.registry();
    }
}
