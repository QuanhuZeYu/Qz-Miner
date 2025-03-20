package club.heiqi.qz_miner.statueStorage;

import club.heiqi.qz_miner.MOD_INFO;
import club.heiqi.qz_miner.minerModes.ModeManager;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AllPlayer {
    public static Logger LOG = LogManager.getLogger();
    /**
     * 玩家UUID为键 - 连锁管理器
     */
    public Map<UUID, ModeManager> playerStatueMap = new ConcurrentHashMap<>();

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
        ModeManager modeManager = playerStatueMap.get(uuid);
        if (!playerStatueMap.containsKey(uuid)) {
            modeManager = new ModeManager();
            LOG.info("创建客户端管理器实例");
        }
        // 无论是否存在都进行覆盖逻辑
        modeManager.player = player;
        modeManager.world = player.worldObj;
        modeManager.register();
        playerStatueMap.put(uuid, modeManager);
    }
    public void clientUnRegister(EntityPlayer player) {
        UUID uuid = player.getUniqueID();
        if (!playerStatueMap.containsKey(uuid)) return;
        ModeManager modeManager = playerStatueMap.get(uuid);
        modeManager.unregister();
        playerStatueMap.remove(uuid);
    }
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
        if (playerStatueMap.containsKey(uuid)) {
            LOG.info("玩家: {} 已在缓存中，无需再次创建", player.getDisplayName());
            modeManager = playerStatueMap.get(uuid);
            modeManager.player = player;
            modeManager.world = player.worldObj;
        } else {
            LOG.info("玩家: {} 不在连锁缓存，已创建新管理器", player.getDisplayName());
            modeManager = new ModeManager();
            modeManager.player = player;
            modeManager.world = player.worldObj;
            playerStatueMap.put(uuid, modeManager);
            modeManager.register();
        }
    }
    public void serverUnRegister(EntityPlayer player) {
        UUID uuid = player.getUniqueID();
        if (!playerStatueMap.containsKey(uuid)) {
            LOG.info("玩家: {} 不存在连锁缓存中，无需卸载", player.getDisplayName());
            return;
        } else {
            ModeManager modeManager = playerStatueMap.get(uuid);
            modeManager.unregister();
            playerStatueMap.remove(uuid);
            LOG.info("玩家: {} 已从连锁缓存中移除", player.getDisplayName());
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
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            clientRegister(player);
        } else {
            // 服务端逻辑
            serverRegister(player);
        }
    }

    /**
     * 退出时清理 - 清空连锁状态
     * @param event
     */
    @SubscribeEvent
    public void qz_onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayer player = event.player;
        UUID playerUUID = player.getUniqueID();
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            // 客户端逻辑
            clientUnRegister(player);
        }
        if (playerStatueMap.containsKey(playerUUID)) {
            serverUnRegister(player);
        }
    }

    /*@SubscribeEvent
    public void qz_onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!(event.entity instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) event.entity;
        if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
            clientRegister(player);
        } else {
            serverRegister(player);
        }
    }

    @SubscribeEvent
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
