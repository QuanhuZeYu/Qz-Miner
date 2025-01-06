package club.heiqi.qz_miner;

import club.heiqi.qz_miner.client.debug.Debug;
import club.heiqi.qz_miner.eventIn.BlockBreakEvent;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import club.heiqi.qz_miner.util.CheckCompatibility;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

import static club.heiqi.qz_miner.Mod_Main.*;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        config.init(event.getSuggestedConfigurationFile());
        config.register();

        allPlayerStorage.register(); // 注册存储玩家连锁状态的容器类
        BlockBreakEvent.register();

        qzMinerNetWork = new QzMinerNetWork(); // 初始化网络通信
        Debug debug = new Debug();
        debug.register();
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {}

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        CheckCompatibility.checkAll(); // 检查是否含有粗矿类
    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        qzMinerCommand.register(event);
    }
}
