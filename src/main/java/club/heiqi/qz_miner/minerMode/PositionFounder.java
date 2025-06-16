package club.heiqi.qz_miner.minerMode;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class PositionFounder {
    public static Logger LOG = LogManager.getLogger();

    /**缓存所持有的调用者 模式*/
    public AbstractMode mode;
    /**标记多线程是否可以安全执行*/
    public boolean canRun = false;

    public PositionFounder(AbstractMode mode) {
        this.mode = mode;

        register();
    }

    @SubscribeEvent
    public void run(TickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            canRun = true;
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            canRun = false;
            return;
        }
    }


    public void register() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    public void unregister() {
        MinecraftForge.EVENT_BUS.unregister(this);
        FMLCommonHandler.instance().bus().unregister(this);
    }
}
