package club.heiqi.qz_miner;

import club.heiqi.qz_miner.eventIn.PlayerInteractive;
import club.heiqi.qz_miner.lootGame.FindMines;
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

        qzMinerNetWork = new QzMinerNetWork(event); // 初始化网络通信

        PlayerInteractive.INSTANCE.register(event); // 注册玩家交互事件监听器
    }

    //加载“进行你的mod设置。构建你关心的任何数据结构。注册食谱。” （不需要的话可以删除）
    public void init(FMLInitializationEvent event) {
        CheckCompatibility.checkAll(); // 检查是否含有粗矿类
        if (CheckCompatibility.isHasClass_MSMTile) {
            FindMines.register();
        }
    }

    // postInit“处理与其他模组的交互，基于此完成您的设置。” （不需要的话可以删除）
    public void postInit(FMLPostInitializationEvent event) {

    }

    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        qzMinerCommand.register(event);
    }
}
