package club.heiqi.qz_miner;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

/**
 * 客户端代理类
 * 处理客户端特定的初始化逻辑
 */
public class ClientProxy extends CommonProxy {
    
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        
        // 客户端预初始化逻辑
        // 例如：键绑定、渲染注册等
    }
    
    // 可以重写其他方法添加客户端特定逻辑
}