package club.heiqi.qz_miner.core;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerManager {
    public static Logger LOG = LogManager.getLogger();
    public Map<UUID, Manager> managers = new HashMap<>();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP playerMP) {
            Manager manager = new Manager(playerMP);
            managers.put(playerMP.getUniqueID(), manager);
            manager.registry();
            LOG.info("注册 玩家: {}: {}", playerMP.getDisplayName(), playerMP.getUniqueID());
        }
    }

    /**可能是掉线触发的登出事件 -> 此时玩家可能仍在连锁过程中*/
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP playerMP) {
            Manager manager = managers.get(playerMP.getUniqueID());
            if (manager == null) {
                LOG.warn("卸载管理器时未找到 玩家: {}: {}", playerMP.getDisplayName(), playerMP.getUniqueID());
                return;
            }
            manager.unRegistry();
            managers.remove(playerMP.getUniqueID());
            LOG.info("卸载 玩家: {}: {}", playerMP.getDisplayName(), playerMP.getUniqueID());
        }
    }

    public void registry() {
        // 只在服务端注册
        FMLCommonHandler.instance().bus().register(this);
        System.out.println("注册玩家管理器");
    }
}
