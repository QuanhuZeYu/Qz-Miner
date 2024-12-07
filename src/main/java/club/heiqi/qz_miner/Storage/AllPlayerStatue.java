package club.heiqi.qz_miner.Storage;

import club.heiqi.qz_miner.MOD_INFO;
import club.heiqi.qz_miner.MY_LOG;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;

import static club.heiqi.qz_miner.MY_LOG.logger;


public class AllPlayerStatue {
    // 创建一个LinkHashSet存储玩家列表
    public static Set<UUID> playerList = new LinkedHashSet<>();
    // 创建一个MAP,键为UUID,值为ChainModeProxy.currentMode
    public static Map<UUID, Statue> playerModeMap = new HashMap<>();

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new AllPlayerStatue());
        FMLCommonHandler.instance().bus().register(new AllPlayerStatue());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent loginEvent) {
        EntityPlayerMP player = (EntityPlayerMP) loginEvent.player;
        UUID uuid = player.getUniqueID();
        playerList.add(uuid);
        playerModeMap.put(uuid, new Statue());
        logger.info("玩家: {} 已进入, 已创建该玩家的连锁实例.", player.getDisplayName());
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerList.remove(event.player.getUniqueID());
        playerModeMap.remove(event.player.getUniqueID());
        logger.info("玩家: {} 已退出, 卸载该玩家的连锁实例.", event.player.getDisplayName());
    }

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
                return serverPatch >= clientPatch;
            } else {
                return false;
            }
        } else {
            return true;
        }
    }

    public static Statue getStatue(UUID uuid) {
        if(playerModeMap.get(uuid) == null) {
            playerModeMap.put(uuid, new Statue());
            MY_LOG.logger.info("玩家: {}; 的连锁模式状态为空, 已创建", uuid);
        }
        return playerModeMap.get(uuid);
    }
}
