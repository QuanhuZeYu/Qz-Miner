package club.heiqi.qz_miner.chain.eventbus;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 主线程 drain 接入点：在 {@link TickEvent.ServerTickEvent#START} 阶段调用 {@link ChainEventBus#drain()}。
 *
 * <p>阶段 1 仅定义类，{@link #bootstrap()} 不在 {@link club.heiqi.qz_miner.MyMod#init} 调用，
 * 休眠等待阶段 2 状态机接入。</p>
 */
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
     * 向 FML 事件总线注册自身。阶段 1 定义但<b>不调用</b>，保持休眠。
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
