package club.heiqi.qz_miner;

import club.heiqi.qz_miner.config.ConfigManager;
import club.heiqi.qz_miner.core.player.PlayerManager;
import club.heiqi.qz_miner.event.EventManager;
import club.heiqi.qz_miner.log.LogManager;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * 模组主类
 * 负责模组的初始化和生命周期管理
 */
@Mod(
    modid = Constant.MODID,
    version = Constant.VERSION,
    name = Constant.MOD_NAME,
    acceptedMinecraftVersions = "[1.7.10]",
    guiFactory = "club.heiqi.qz_miner.client.configGUI.QzMinerConfigGUIFactory",
    dependencies = "required-after:qz_uilib;"
)
public class MyMod {
    
    // 代理类
    @SidedProxy(clientSide = Constant.CLIENT_PROXY, serverSide = Constant.COMMON_PROXY)
    public static CommonProxy proxy;
    
    // 配置管理器
    public static final ConfigManager configManager = new ConfigManager();
    
    // 玩家管理器
    public static PlayerManager playerManager;
    
    /**
     * 预初始化阶段
     * @param event 事件
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LogManager.info("=== Qz Miner 预初始化开始 ===");
        
        // 初始化配置管理器
        configManager.init(event.getSuggestedConfigurationFile());
        
        // 设置日志跟踪状态
        LogManager.setTraceEnabled(configManager.getCurrentConfig().isEnableTraceLog());
        
        LogManager.info("配置管理器初始化完成");
        
        // 调用代理的预初始化
        proxy.preInit(event);
        
        LogManager.info("=== Qz Miner 预初始化完成 ===");
    }
    
    /**
     * 初始化阶段
     * @param event 事件
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        LogManager.info("=== Qz Miner 初始化开始 ===");
        
        // 初始化事件管理器
        EventManager.init();
        LogManager.info("事件管理器初始化完成");
        
        // 初始化玩家管理器
        playerManager = new PlayerManager();
        playerManager.register();
        LogManager.info("玩家管理器初始化完成");
        
        // 调用代理的初始化
        proxy.init(event);
        
        LogManager.info("=== Qz Miner 初始化完成 ===");
    }
    
    /**
     * 后初始化阶段
     * @param event 事件
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        LogManager.info("=== Qz Miner 后初始化开始 ===");
        
        // 调用代理的后初始化
        proxy.postInit(event);
        
        LogManager.info("=== Qz Miner 后初始化完成 ===");
    }
    
    /**
     * 服务器启动阶段
     * @param event 事件
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        LogManager.info("=== Qz Miner 服务器启动 ===");
        
        // 注册命令（如果需要）
        // event.registerServerCommand(new SomeCommand());
        
        LogManager.info("=== Qz Miner 服务器启动完成 ===");
    }
}