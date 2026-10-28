package club.heiqi.qz_miner.chain.eventbus;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** 客户端 tick START 阶段消费独立事件队列，将网络阶段快照分发给客户端投影订阅者。 */
@SideOnly(Side.CLIENT)
public class ClientChainEventBusDrainer {

    /** 注入的客户端总线实例。 */
    private final ChainEventBus bus;

    /**
     * @param bus 注入的客户端总线
     */
    public ClientChainEventBusDrainer(ChainEventBus bus) {
        this.bus = bus;
    }

    /**
     * 向 FML 事件总线注册自身。客户端 {@code init}（见 {@code ClientProxy}）调用，
     * 每 client tick {@code START} 阶段触发一次 {@link ChainEventBus#drain()}。
     */
    public void bootstrap() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 客户端 tick 回调，在 START 阶段 drain。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        bus.drain();
    }
}