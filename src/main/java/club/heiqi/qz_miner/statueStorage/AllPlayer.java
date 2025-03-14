package club.heiqi.qz_miner.statueStorage;

import club.heiqi.qz_miner.MOD_INFO;
import club.heiqi.qz_miner.minerModes.ModeManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AllPlayer {
    public static Logger LOG = LogManager.getLogger();
    /**
     * 玩家UUID为键 - 连锁管理器
     */
    public Map<UUID, ModeManager> playerStatueMap = new ConcurrentHashMap<>();


    /**
     * 通用:
     *  进入世界时收集信息 - 初始化连锁状态
     * @param event
     */
    @SubscribeEvent
    public void qz_onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayer player = event.player;
        UUID uuid = event.player.getUniqueID();
        ModeManager modeManager;
        if (playerStatueMap.containsKey(uuid)) {
            LOG.info("玩家: {} 已在缓存连锁实例中，无需再次创建", player.getDisplayName());
            modeManager = playerStatueMap.get(uuid);
        }else {
            LOG.info("玩家: {} 已登录，缓存连锁实例中不存在，已创建", player.getDisplayName());
            modeManager = new ModeManager();
        }
        modeManager.player = player;
        modeManager.world = player.worldObj;
        playerStatueMap.put(uuid, modeManager);
        modeManager.register();
    }

    /**
     * 退出时清理 - 清空连锁状态
     * @param event
     */
    @SubscribeEvent
    public void qz_onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayer player = event.player;
        UUID playerUUID = player.getUniqueID();
        if (playerStatueMap.containsKey(playerUUID)) {
            LOG.info("玩家: {} 已登出，连锁实例中已删除", player.getDisplayName());
            playerStatueMap.remove(playerUUID);
        } else {
            LOG.info("玩家: {} 已登出，连锁实例中不存在，无需删除", player.getDisplayName());
        }
    }

    @SubscribeEvent
    public void qz_onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.world.isRemote) {
            if (event.entity.getUniqueID() == Minecraft.getMinecraft().thePlayer.getUniqueID()) {
                EntityPlayer player = (EntityPlayer) event.entity;
                UUID uuid = event.entity.getUniqueID();
                ModeManager modeManager;
                if (playerStatueMap.containsKey(uuid)) {
                    LOG.info("玩家: {} 已在缓存连锁实例中，无需再次创建", player.getDisplayName());
                    modeManager = playerStatueMap.get(uuid);
                }else {
                    LOG.info("玩家: {} 已登录，缓存连锁实例中不存在，已创建", player.getDisplayName());
                    modeManager = new ModeManager();
                }
                modeManager.player = player;
                modeManager.world = player.worldObj;
                playerStatueMap.put(uuid, modeManager);
                modeManager.register();
            }
        }
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void qz_onEntityOutWorld(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        UUID playerUUID = player.getUniqueID();
        if (playerStatueMap.containsKey(playerUUID)) {
            LOG.info("玩家: {} 已登出，连锁实例中已删除", player.getDisplayName());
            playerStatueMap.remove(playerUUID);
        } else {
            LOG.info("玩家: {} 已登出，连锁实例中不存在，无需删除", player.getDisplayName());
        }
    }

    @SubscribeEvent
    public void qz_onChangeWorld(PlayerEvent.PlayerChangedDimensionEvent event) {
        EntityPlayer player = event.player;
        UUID uuid = player.getUniqueID();
        if (playerStatueMap.containsKey(uuid)) {
            LOG.info("玩家 {} 切换世界");
            ModeManager modeManager = playerStatueMap.get(uuid);
            modeManager.player = player;
            modeManager.world = player.worldObj;
        }
    }

    /**
     * 兼容性检查
     * @param mods
     * @param side
     * @return
     */
    @NetworkCheckHandler
    public boolean checkClientVersion(Map<String, String> mods, Side side) {
        if (mods.isEmpty()) {
            return true;
        }
        if (mods.containsKey(MOD_INFO.MODID)) {
            String clientVersion = mods.get(MOD_INFO.MODID);
            String serverVersion = MOD_INFO.VERSION;
            if (serverVersion.equals(clientVersion) || serverVersion.startsWith(clientVersion) || clientVersion.startsWith(serverVersion)) {
                return true;
            }
            int clientMajor = Integer.parseInt(clientVersion.split("\\.")[0]);
            int clientMinor = Integer.parseInt(clientVersion.split("\\.")[1]);
            int clientPatch = Integer.parseInt(clientVersion.split("\\.")[2]);
            int serverMajor = Integer.parseInt(serverVersion.split("\\.")[0]);
            int serverMinor = Integer.parseInt(serverVersion.split("\\.")[1]);
            int serverPatch = Integer.parseInt(serverVersion.split("\\.")[2]);
            if (serverMajor == clientMajor && serverMinor == clientMinor) {
                return true;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
