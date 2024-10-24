package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import club.heiqi.qz_miner.MineModeSelect.MethodHelper.PointFonder.PointFonder_Rectangular;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import static club.heiqi.qz_miner.MineModeSelect.MethodHelper.BlockMethodHelper.getOutBoundOfPoint;

public class CenterRectangularMode extends AbstractMiner {

    @Override
    public boolean addPointFonder() {
        pointFonder = new PointFonder_Rectangular(center);
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
