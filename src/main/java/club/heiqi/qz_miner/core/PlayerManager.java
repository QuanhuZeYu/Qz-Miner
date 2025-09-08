package club.heiqi.qz_miner.core;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

public class PlayerManager {
    public static Logger LOG = LogManager.getLogger();
    public Map<EntityPlayerMP, Manager> managers = new HashMap<>();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.player instanceof EntityPlayerMP playerMP) {
            Manager manager = new Manager(playerMP);
            managers.put(playerMP, manager);
            manager.registry();
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP playerMP) {
            Manager manager = managers.get(playerMP);
            if (manager == null) {
                LOG.warn("卸载管理器时未找到 玩家: {}", playerMP);
                return;
            }
            manager.unRegistry();
            managers.remove(playerMP);
        }
    }

    public void registry() {
        // 只在服务端注册
        FMLCommonHandler.instance().bus().register(this);
        System.out.println("注册玩家管理器");
    }
}
