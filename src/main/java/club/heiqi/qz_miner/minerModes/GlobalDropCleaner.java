package club.heiqi.qz_miner.minerModes;

import club.heiqi.qz_miner.Config;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static club.heiqi.qz_miner.minerModes.ModeManager.GLOBAL_DROPS;

public class GlobalDropCleaner {
    public static Logger LOG = LogManager.getLogger();
    public static long lastGlobalChangeTime = 0;

    @SubscribeEvent
    public static void globalCleaner(TickEvent.ServerTickEvent event) {
        if (!Config.dropItemToSelf) return;
        // 10s没有更新便清理掉所有内容物
        if (System.currentTimeMillis() - lastGlobalChangeTime >= 10_000) {
            if (!GLOBAL_DROPS.isEmpty()) {
                GLOBAL_DROPS.clear();
                LOG.info("全局表已清理");
            }
        }
    }
}
