package club.heiqi.qz_miner;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * 通用代理类
 * 处理服务端和客户端通用的初始化逻辑
 */
public class CommonProxy {
    
    /**
     * 预初始化
     * @param event 事件
     */
    public void preInit(FMLPreInitializationEvent event) {
        // 通用预初始化逻辑
    }
    
    /**
     * 初始化
     * @param event 事件
     */
    public void init(FMLInitializationEvent event) {
        // 通用初始化逻辑
    }
    
    /**
     * 后初始化
     * @param event 事件
     */
    public void postInit(FMLPostInitializationEvent event) {
        // 通用后初始化逻辑
    }
    
    /**
     * 服务器启动
     * @param event 事件
     */
    public void serverStarting(FMLServerStartingEvent event) {
        // 服务器启动逻辑
    }
}