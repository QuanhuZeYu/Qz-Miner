package club.heiqi.qz_miner;

import club.heiqi.qz_miner.core.PlayerManager;
import club.heiqi.qz_miner.network.NetworkMain;
import club.heiqi.qz_miner.thread.ParallelTick;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = Constant.MODID,
        version = Tags.VERSION,
        name = Constant.MOD_NAME,
        acceptedMinecraftVersions = "[1.7.10]",
        guiFactory = "club.heiqi.qz_miner.client.configGUI.QzMinerConfigGUIFactory",
        dependencies = "required-after:qz_uilib;",
        acceptableRemoteVersions = "[4.3.0,5.0.0)"
)
public class MyMod {
    public Logger LOG = LogManager.getLogger();

    @SidedProxy(clientSide = Constant.CLIENT_PROXY, serverSide = Constant.COMMON_PROXY)
    public static CommonProxy proxy;

    public static Config config = new Config();
    public static NetworkMain networkMain = new NetworkMain();
    public static PlayerManager playerManager;  // 在客户端使用此字段可能会报错
    public static ParallelTick parallelTick = new ParallelTick();

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
