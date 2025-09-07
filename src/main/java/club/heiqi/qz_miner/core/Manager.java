package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.core.opertator.BaseOperator;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

/**
 * 以玩家为核心的连锁管理器
 * 容器核心为玩家
 */
public class Manager {
    public Logger LOG = LogManager.getLogger();
    public EntityPlayerMP player;
    public MinerConfig pConfig = new MinerConfig();
    public MinerModeState minerModeState = new MinerModeState();
    /**是否按下连锁键*/
    public boolean inPressChainKey = false;
    public boolean inChain = false;

    public Manager(EntityPlayerMP player) {
        this.player = player;
    }

    public BaseOperator operator = null;
    @SubscribeEvent
    public void onBlockBreakEvent(BlockEvent.BreakEvent event) {
        // 事件触发者 1.不是玩家自己 2.不是服务器玩家类 3.不是服务器线程 任意一个满足 不处理
        if (!event.getPlayer().getUniqueID().equals(player.getUniqueID())
                || !(event.getPlayer() instanceof EntityPlayerMP)
                || !Thread.currentThread().getName().toLowerCase().contains("server")
        ) {
            return;
        }
        // 正在连锁中 或 未按下连锁键 不处理 避免重复触发连锁
        if (inChain || !inPressChainKey) {
            return;
        }
        inChain = true;

        Vector3i pos = new Vector3i(event.x, event.y, event.z);
        // ==========  触发连锁  ==========
        operator = new BaseOperator(pos, this);
    }

    public void registry() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("注册成功");
    }

    public void unRegistry() {
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        LOG.info("注销成功");
    }

    public void receiveClientConfig(MinerConfig minerConfig) {
        pConfig.bigRadius = Math.min(minerConfig.bigRadius, Config.bigRadius);
        pConfig.blockLimit = Math.min(minerConfig.blockLimit, Config.blockLimit);
        pConfig.smallRadius = Math.min(minerConfig.smallRadius, Config.smallRadius);
    }


}
