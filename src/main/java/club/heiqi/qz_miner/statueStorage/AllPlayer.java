package club.heiqi.qz_miner.statueStorage;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static club.heiqi.qz_miner.MY_LOG.logger;

public class AllPlayer {
    public Map<UUID, PlayerStatue> playerStatueMap = new HashMap<>();

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID uuid = player.getUniqueID();
        if (playerStatueMap.containsKey(uuid)) {
            logger.info("玩家: {} 已在缓存连锁实例中，无需再次创建", uuid);
        } else {
            logger.info("玩家: {} 已登录，缓存连锁实例中不存在，已创建", uuid);
            playerStatueMap.put(uuid, new PlayerStatue(player));
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID playerUUID = player.getUniqueID();
        if (playerStatueMap.containsKey(playerUUID)) {
            logger.info("玩家: {} 已登出，连锁实例中已删除", playerUUID);
            playerStatueMap.remove(playerUUID);
        } else {
            logger.info("玩家: {} 已登出，连锁实例中不存在，无需删除", playerUUID);
        }
    }

    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }
}
