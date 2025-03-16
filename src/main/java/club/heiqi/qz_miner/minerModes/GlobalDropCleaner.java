package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.minerModes.utils.Utils;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static club.heiqi.qz_miner.minerModes.ModeManager.GLOBAL_DROPS;

public class GlobalDropCleaner {
    public static Logger LOG = LogManager.getLogger();
    public static long lastGlobalChangeTime = 0;

    private GlobalDropCleaner() {}

    @SubscribeEvent
    public void globalCleaner(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) return;
        if (!Config.dropItemToSelf) return;
        // 10s没有更新便清理掉所有内容物
        if (System.currentTimeMillis() - lastGlobalChangeTime >= 3_000) {
            if (!GLOBAL_DROPS.isEmpty()) {
            Iterator<Map.Entry<Vector3i, ConcurrentLinkedQueue<EntityItem>>> it = GLOBAL_DROPS.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Vector3i, ConcurrentLinkedQueue<EntityItem>> entry = it.next();
                    Vector3i position = entry.getKey();
                    ConcurrentLinkedQueue<EntityItem> queue = entry.getValue();
                    it.remove(); // 原子移除，避免后续干扰
                    EntityItem item;
                    while ((item = queue.poll()) != null) {
                        World world = item.worldObj;
                        // 寻找最近的玩家
                        double minDistSq = Double.MAX_VALUE;
                        EntityPlayer closest = null;
                        List<EntityPlayer> players = new ArrayList<>(world.playerEntities);
                        for(EntityPlayer player : players) {
                            if (player.dimension != item.dimension) continue;
                            double dx = player.posX - item.posX;
                            double dy = player.posY - item.posY;
                            double dz = player.posZ - item.posZ;
                            double distSq = dx*dx + dy*dy + dz*dz;
                            if (dx>16 || dz>16 || dy>16) continue;
                            if (distSq < minDistSq) {
                                minDistSq = distSq;
                                closest = player;
                            }
                        }
                        if (closest != null) {
                            Vector3f dropPos = Utils.getItemDropPos(closest);
                            item.setPosition(dropPos.x, dropPos.y, dropPos.z);
                        }
                        world.spawnEntityInWorld(item);
                    }
                }
                LOG.info("全局表已清理");
            }
        }
    }
    public static void register() {
        GlobalDropCleaner cleaner = new GlobalDropCleaner();
        FMLCommonHandler.instance().bus().register(cleaner);
        MinecraftForge.EVENT_BUS.register(cleaner);
    }
}
