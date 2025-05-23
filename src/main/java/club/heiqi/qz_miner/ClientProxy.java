package club.heiqi.qz_miner;

import club.heiqi.qz_miner.client.RenderUtils;
import club.heiqi.qz_miner.client.playerInput.PlayerInput;
import club.heiqi.qz_miner.util.CheckCompatibility;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {
    public PlayerInput playerInput;

    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        playerInput = new PlayerInput().register();
        RenderUtils.register();
        // 计算渲染方块枚举 - 为mac兼容不再在初始化时预渲染
        // if (!RenderCube.isInit) RenderCube.init();
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        if (CheckCompatibility.isHasClass_MSMTile) {

        }
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }
}
