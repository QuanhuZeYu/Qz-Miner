package club.heiqi.qz_miner;

import club.heiqi.qz_miner.Command.MinerCommand;
import club.heiqi.qz_miner.Core.CoreLogic_BlockBreaker;
import club.heiqi.qz_miner.Storage.AllPlayerStatue;
import club.heiqi.qz_miner.Util.CheckCompatibility;
import club.heiqi.qz_miner.network.Qz_MinerSimpleNetwork;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.sync(event.getSuggestedConfigurationFile());

        // 方块连锁测试
        CoreLogic_BlockBreaker.register();

        new Qz_MinerSimpleNetwork();
        AllPlayerStatue.register();
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        CheckCompatibility.isHasClass_BlockOresAbstract(); // 检查是否含有粗矿类
    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        Mod_Main.server = event.getServer();
        MinerCommand.register(event);
    }
}
