package club.heiqi.qz_miner;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import club.heiqi.qz_miner.core.PlayerManager;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(
    modid = MyMod.MODID,
    version = Tags.VERSION,
    name = MyMod.MOD_NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    guiFactory = "club.heiqi.qz_miner.client.configGUI.QzMinerConfigGUIFactory")
public class MyMod {

    public static final String MODID = "qz_miner";
    public static final String MOD_NAME = "Qz Miner";
    public static final Logger LOG = LogManager.getLogger(MODID);
    public static final Config CONFIG = new Config();
    public static PlayerManager playerManager;

    @SidedProxy(clientSide = "club.heiqi.qz_miner.ClientProxy", serverSide = "club.heiqi.qz_miner.CommonProxy")
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
        playerManager = new PlayerManager();
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
