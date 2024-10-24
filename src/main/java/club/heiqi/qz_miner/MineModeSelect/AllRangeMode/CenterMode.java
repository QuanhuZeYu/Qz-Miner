package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import club.heiqi.qz_miner.MineModeSelect.MethodHelper.PointFonder.PointFonder_Center;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class CenterMode extends AbstractMiner {

    @Override
    public boolean addPointFonder() {
        pointFonder = new PointFonder_Center(center);
        return true;
    }

    /**
     * 这个方法要重写并添加Event注解才有效!!!!!
     *
     * @param event
     */
    @Override
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        super.onTick(event);
    }

}
