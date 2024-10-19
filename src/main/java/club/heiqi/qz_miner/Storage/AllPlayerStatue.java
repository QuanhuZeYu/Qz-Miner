package club.heiqi.qz_miner.Storage;

import club.heiqi.qz_miner.MY_LOG;
import club.heiqi.qz_miner.MineModeSelect.Statue;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;


public class AllPlayerStatue {
    // 创建一个LinkHashSet存储玩家列表
    public static Set<UUID> playerList = new LinkedHashSet<>();
    // 创建一个MAP,键为UUID,值为ChainModeProxy.currentMode
    public static Map<UUID, Statue> playerModeMap = new HashMap<>();

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new AllPlayerStatue());
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent loginEvent) {
        EntityPlayerMP player = (EntityPlayerMP) loginEvent.player;
        UUID uuid = player.getUniqueID();
        playerList.add(uuid);
        playerModeMap.put(uuid, new Statue());
    }

    public static Statue getStatue(UUID uuid) {
        if(playerModeMap.get(uuid) == null) {
            playerModeMap.put(uuid, new Statue());
            MY_LOG.LOG.info("玩家: {}; 的连锁模式状态为空, 已创建", uuid);
        }
        return playerModeMap.get(uuid);
    }
}
