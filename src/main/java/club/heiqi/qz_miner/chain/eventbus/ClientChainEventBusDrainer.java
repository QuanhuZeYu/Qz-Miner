package club.heiqi.qz_miner.chain.eventbus;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端主线程 drain 接入点：在 {@link TickEvent.ClientTickEvent#START} 阶段调用
 * {@link ChainEventBus#drain()}。
 *
 * <p>守决策4（客户端总线仅空跑骨架）：客户端不 publish 任何客户端事件、不持有独立状态机，
 * 仅为预览订阅接入预留 drain 通道。预览订阅留阶段6接入。</p>
 *
 * <p>平行类设计（分歧4裁决）：不抽继承基类与 {@link ChainEventBusDrainer} 平行，
 * 仅订阅 {@link TickEvent.ClientTickEvent} 而非 {@code ServerTickEvent}。
 * 加 {@link SideOnly}({@link Side#CLIENT}) 注解确保不进入服务端类加载。</p>
 */
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
     * 客户端 tick 回调，仅 START 阶段 drain。当前为空跑骨架。
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