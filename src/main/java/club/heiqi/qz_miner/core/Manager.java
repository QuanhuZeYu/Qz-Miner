package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.core.founder.DeterminingIdentical;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.UUID;

/**
 * 以玩家UUID为核心的连锁管理器
 * 容器核心为玩家
 */
public class Manager {
    public Logger LOG = LogManager.getLogger();
    public EntityPlayerMP player;
    public final UUID playerUUID;
    public MinerConfig pConfig = new MinerConfig();
    public MinerModeState minerModeState = new MinerModeState();
    /**是否按下连锁键*/
    public boolean inPressChainKey = false;
    public boolean inOperate = false;

    public Manager(EntityPlayerMP player) {
        this.player = player;
        playerUUID = player.getUniqueID();
    }

    public BaseOperator operator = null;
    @SubscribeEvent
    public void onBlockBreakEvent(BlockEvent.BreakEvent event) {
        // 0.是交互模式 1.不是玩家自己 2.不是服务器玩家类 3.不是服务器线程 任意一个满足 不处理
        if (minerModeState.isInteractMode() ||
                !event.getPlayer().getUniqueID().equals(playerUUID) ||
                !(event.getPlayer() instanceof EntityPlayerMP) ||
                !Thread.currentThread().getName().toLowerCase().contains("server")
        ) return;
        // 正在连锁中 或 未按下连锁键 不处理 避免重复触发连锁
        if (inOperate || !inPressChainKey) {
            return;
        }
        inOperate = true;

        Vector3i pos = new Vector3i(event.x, event.y, event.z);
        // ==========  触发连锁  ==========
        player = (EntityPlayerMP) event.getPlayer();
        operator = new BaseOperator(pos, this);
        operator.registry();
    }

    /**Bottom = 0, Top = 1, East = 2, West = 3, North = 4, South = 5.*/
    public int hitSide = 1;
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteractEvent(PlayerInteractEvent event) {
        if (!minerModeState.isInteractMode() ||
                !event.entityPlayer.getUniqueID().equals(playerUUID) ||
                !(event.entityPlayer instanceof EntityPlayerMP) ||
                !Thread.currentThread().getName().toLowerCase().contains("server")
        ) return;
        // 正在连锁中 或 未按下连锁键 不处理 避免重复触发连锁
        if (inOperate || !inPressChainKey) {
            return;
        }
        inOperate = true;

        Vector3i pos = new Vector3i(event.x, event.y, event.z);
        hitSide = event.face;
        // ==========  触发连锁  ==========
        player = (EntityPlayerMP) event.entityPlayer;
        operator = new InteractOperator(pos, this);
        operator.registry();
    }

    public ArrayList<ItemStack> drops = new ArrayList<>();
    @SubscribeEvent
    public void onHarvestDropEvent(BlockEvent.HarvestDropsEvent event) {
        // 事件触发者 0.掉落物没有收获者 1.不是玩家自己 2.不是服务器玩家类 3.不是服务器线程 任意一个满足 不处理
        if (event.harvester == null ||
                !event.harvester.getUniqueID().equals(playerUUID) ||
                !(event.harvester instanceof EntityPlayerMP) ||
                !Thread.currentThread().getName().toLowerCase().contains("server")
        ) return;
        // 该管理器只收集此 player 的掉落物

        // 未在连锁不处理 - 检查drops收集容器是否有东西 此时掉落到地面
        if (!inOperate) {
            return;
        }

        // 收集掉落物
        for (ItemStack drop : event.drops) {
            boolean merged = false;  // 是否合并到容器内了
            // 对比收集容器中的
            for (ItemStack container : new ArrayList<>(drops)) {
                if (!DeterminingIdentical.isSame(container, drop)) continue;
                container.stackSize += drop.stackSize;
                drop.stackSize = 0;
                merged = true;
            }
            if (!merged) drops.add(drop);
        }

        // 阻止原始掉落
        event.drops.clear();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.WorldTickEvent.Phase.START) return;
        if (!inOperate && !inPressChainKey) dropCollects();
    }

    public void dropCollects() {
        // 只收集自己掉落的东西!
        if (!drops.isEmpty()) {
            for (ItemStack itemStack : drops) {
                player.worldObj.spawnEntityInWorld(
                        new EntityItem(player.worldObj, player.posX, player.posY, player.posZ, itemStack)
                );
            }
            drops.clear();
        }
    }

    public void registry() {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("注册成功");
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        cleanupState();
    }

    public void unRegistry() {
        cleanupState();
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        LOG.info("注销成功");
    }

    public void cleanupState() {
        inPressChainKey = false;
        inOperate = false;
    }

    /**从网络接收来自客户端的配置*/
    public void receiveClientConfig(MinerConfig minerConfig) {
        pConfig.bigRadius = Math.max(Math.min(minerConfig.bigRadius, Config.bigRadius), 0);
        pConfig.blockLimit = Math.max(Math.min(minerConfig.blockLimit, Config.blockLimit), 0);
        pConfig.smallRadius = Math.max(Math.min(minerConfig.smallRadius, Config.smallRadius), 0);
        pConfig.tunnelWidth = Math.max(Math.min(minerConfig.tunnelWidth, Config.tunnelWidth), 0);
        pConfig.useChainDoneMessage = minerConfig.useChainDoneMessage;
    }
}
