package club.heiqi.qz_miner.client;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * HUD 内容冲刷点：客户端 tick 末尾把业务状态写入 HUD signal。
 *
 * <p>UILib 4.9 宿主按帧消费 signal，但写侧必须由业务方在客户端主线程完成；Miner 的客户端状态
 * 全部在 tick 内推进，故每 tick 末尾冲刷一次即可与旧「每帧快照」行为等价（状态变化只发生在 tick）。
 * 值不变时 {@link QzMinerHudWindow#refresh()} 不写 signal。</p>
 */
@SideOnly(Side.CLIENT)
public final class QzMinerHudTicker {

    private final QzMinerHudWindow window;

    /**
     * 创建冲刷器。
     *
     * @param window 连锁状态 HUD 窗口
     */
    public QzMinerHudTicker(QzMinerHudWindow window) {
        if (window == null) {
            throw new IllegalArgumentException("window 不可为 null");
        }
        this.window = window;
    }

    /** 注册到 FML 事件总线（客户端主线程）。 */
    public void register() {
        FMLCommonHandler.instance().bus().register(this);
    }

    /**
     * 客户端 tick 末尾冲刷 HUD 内容。
     *
     * @param event 客户端 tick 事件
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event == null || event.phase != TickEvent.Phase.END) {
            return;
        }
        window.refresh();
    }
}
