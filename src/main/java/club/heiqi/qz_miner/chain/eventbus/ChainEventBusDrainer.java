package club.heiqi.qz_miner.chain.eventbus;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** 服务端 tick START 阶段串行消费连锁事件；由 MyMod 初始化注册。 */
public class ChainEventBusDrainer {

    /** 注入的总线实例。 */
    private final ChainEventBus bus;

    /**
     * @param bus 注入的总线
     */
    public ChainEventBusDrainer(ChainEventBus bus) {
        this.bus = bus;
    }

    /**
     * 由 {@link club.heiqi.qz_miner.MyMod#init} 向 FML 事件总线注册自身，
     * 每 tick {@code START} 阶段触发一次 {@link ChainEventBus#drain()}。
     */
    public void bootstrap() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 服务端 tick 回调，仅 START 阶段 drain。
     *
     * @param event 服务端 tick 事件
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        bus.drain();
    }
}
