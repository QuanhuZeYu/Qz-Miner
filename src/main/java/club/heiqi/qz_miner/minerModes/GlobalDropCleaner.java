package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3i;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static club.heiqi.qz_miner.minerModes.ModeManager.GLOBAL_DROPS;

public class GlobalDropCleaner {
    public static Logger LOG = LogManager.getLogger();
    public static long lastGlobalChangeTime = 0;

    private GlobalDropCleaner() {}

    @SubscribeEvent
    public void globalCleaner(TickEvent.ServerTickEvent event) {
        if (!Config.dropItemToSelf) return;
        // 10s没有更新便清理掉所有内容物
        if (System.currentTimeMillis() - lastGlobalChangeTime >= 10_000) {
            if (!GLOBAL_DROPS.isEmpty()) {
                for (Map.Entry<Vector3i, ConcurrentLinkedQueue<EntityItem>> entry : GLOBAL_DROPS.entrySet()) {
                    Vector3i position = entry.getKey();
                    ConcurrentLinkedQueue<EntityItem> queue = entry.getValue();
                    // 遍历队列元素（弱一致，可能不反映后续修改）
                    for (EntityItem item : queue) {
                        // 寻找最近的玩家
                        float distance2 = Float.MAX_VALUE;
                        EntityPlayer select = null;
                        for(EntityPlayer player : item.worldObj.playerEntities) {
                            double px = player.posX; double py = player.posY; double pz = player.posZ;
                            double ix = item.posX; double iy = item.posY; double iz = item.posZ;
                            float dx = (float) (px - ix); float dy = (float) (py - iy); float dz = (float) (pz - iz);
                            float calDis2 = dx*dx + dy*dy + dz*dz;
                            if (calDis2 < distance2) {
                                distance2 = calDis2;
                                select = player;
                            }
                        }
                        // 将物品生成到这个玩家脚下 - 删除该元素
                        if (select != null)
                            item.setPosition(select.posX, select.posY+select.eyeHeight, select.posZ);
                        item.worldObj.spawnEntityInWorld(item);
                    }
                    // 遍历完成后删除该键值
                    GLOBAL_DROPS.remove(position);
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
