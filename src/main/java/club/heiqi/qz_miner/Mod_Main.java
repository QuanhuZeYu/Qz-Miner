package club.heiqi.qz_miner;

import club.heiqi.qz_miner.command.QzMinerCommand;
import club.heiqi.qz_miner.network.QzMinerNetWork;
import club.heiqi.qz_miner.statueStorage.AllPlayer;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = MOD_INFO.MODID,
    version = Tags.VERSION,
    name = MOD_INFO.NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    guiFactory = MOD_INFO.GUI_FACTORY_CLASS
)
public class Mod_Main {
    public static AllPlayer allPlayerStorage = new AllPlayer();
    public static QzMinerNetWork qzMinerNetWork;
    public static QzMinerCommand qzMinerCommand = new QzMinerCommand();
    public static Config config = new Config();

    public static final String MODID = MOD_INFO.MODID;
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(clientSide = MOD_INFO.CLIENT_PROXY_CLASS, serverSide = MOD_INFO.SERVER_PROXY_CLASS)
    public static CommonProxy proxy;

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
