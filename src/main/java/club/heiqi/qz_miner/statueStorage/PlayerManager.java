package club.heiqi.qz_miner.statueStorage;

import club.heiqi.qz_miner.minerMode.ModeManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家管理器
 */
public class PlayerManager {
    public static Logger LOG = LogManager.getLogger();
    /**
     * 玩家UUID为键 - 连锁管理器
     */
    public Map<UUID, ModeManager> allPlayer = new ConcurrentHashMap<>();

    public void clientRegister(EntityPlayer player) {
        if (player == null) {
            LOG.warn("player为null拒绝注册连锁实例");
            return;
        }
        if (player instanceof FakePlayer) {
            LOG.warn("假人拒绝注册连锁实例");
            return;
        }
        UUID uuid = player.getUniqueID();
        ModeManager modeManager;
        // 1.缓存已有管理器
        if (allPlayer.containsKey(uuid)) {
            modeManager = allPlayer.get(uuid);
            modeManager.player = player;
            LOG.info("[{}: {}] 客户端管理器重新设置完毕",player.getDisplayName(),modeManager.registryInfo);
        }
        // 2.不存在缓存的管理器
        else {
            modeManager = new ModeManager(player);
            modeManager.player = player;
            allPlayer.put(uuid, modeManager);
        }
    }

    /**
     * 无论是否存在管理器实例都会重新赋值player world
     * @param player
     */
    public void serverRegister(EntityPlayer player) {
        if (player == null) {
            LOG.warn("player为null拒绝注册连锁实例");
            return;
        }
        if (player instanceof FakePlayer) {
            LOG.warn("假人拒绝注册连锁实例");
            return;
        }
        UUID uuid = player.getUniqueID();
        // 服务端逻辑
        ModeManager modeManager;
        // 1.缓存已有管理器
        if (allPlayer.containsKey(uuid)) {
            modeManager = allPlayer.get(uuid);
            modeManager.player = player;
            LOG.info("[{}: {}] 服务端管理器重新设置完毕",player.getDisplayName(),modeManager.registryInfo);
        }
        // 2.不存在缓存的管理器
        else {
            modeManager = new ModeManager(player);
            allPlayer.put(uuid, modeManager);
        }
    }

    public void unregister(EntityPlayer player) {
        UUID uuid = player.getUniqueID();
        if (!allPlayer.containsKey(uuid)) {
            return;
        } else {
            ModeManager modeManager = allPlayer.get(uuid);
            modeManager.unregister();
            allPlayer.remove(uuid);
            LOG.info("玩家: {} 已从连锁缓存中移除", player.getDisplayName());
        }
    }

    public void proxyRegister(EntityPlayer player) {
        Thread thread = Thread.currentThread();
        if (thread.getName().toLowerCase().contains("server")) {
            serverRegister(player);
        }
        else if (thread.getName().toLowerCase().contains("client")) {
            clientRegister(player);
        }
        else {
            clientRegister(player);
        }
    }

    /**
     * 通用:
     *  进入世界时收集信息 - 初始化连锁状态
     * @param event
     */
    @SubscribeEvent
    public void qz_onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayer player = event.player;
        proxyRegister(player);
    }

    /**
     * 退出时清理 - 清空连锁状态
     * @param event
     */
    @SubscribeEvent
    public void qz_onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayer player = event.player;
        unregister(player);
    }

    @SubscribeEvent
    public void qz_onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!(event.entity instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.entity;
        proxyRegister(player);
    }

    /*@SubscribeEvent
    public void qz_onChangeWorld(PlayerEvent.PlayerChangedDimensionEvent event) {
        EntityPlayer player = event.player;
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            clientRegister(player);
        } else {
            serverRegister(player);
        }
    }

    @SubscribeEvent
    public void qz_onPlayerReSpawn(PlayerEvent.PlayerRespawnEvent event) {
        EntityPlayer player = event.player;
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            clientRegister(player);
        } else {
            serverRegister(player);
        }
    }

    @SubscribeEvent
    public void qz_onPlayerClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        EntityPlayer player = event.entityPlayer;
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            clientRegister(player);
        } else {
            serverRegister(player);
        }
    }*/

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
