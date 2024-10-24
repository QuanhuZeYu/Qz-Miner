package club.heiqi.qz_miner.MineModeSelect.AllRangeMode;

import club.heiqi.qz_miner.MineModeSelect.AbstractMiner;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import static club.heiqi.qz_miner.MineModeSelect.MethodHelper.BlockMethodHelper.getOutBoundOfPoint;

public class PlanarRestrictedMode extends AbstractMiner {

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
