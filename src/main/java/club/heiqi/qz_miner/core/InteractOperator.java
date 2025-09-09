package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.utils.MessageUtils;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import org.joml.Vector3i;

public class InteractOperator extends BaseOperator {
    public InteractOperator(Vector3i pos, Manager manager) {
        super(pos, manager);
    }

    @Override
    @SubscribeEvent
    public void operatorTask(TickEvent.ServerTickEvent event) {
        if (!manager.inPressChainKey) {
            this.unRegistry();
        }

        if (canBreakPositions.isEmpty()) {
            return;
        }

        int breakCountInTick = 0;
        Vector3i pos;
        while ((pos = canBreakPositions.poll()) != null) {
            playerMP.theItemInWorldManager.activateBlockOrUseItem(
                    playerMP, playerMP.worldObj,
                    playerMP.getCurrentEquippedItem(),
                    pos.x, pos.y, pos.z,
                    1,
                    0,0,0);

            breakCountInTick++;
            operatorCount++;
            if (breakCountInTick >= 64) {
                return;
            }
        }

        if (positionFounder.stopped.get()) {
            this.unRegistry();
        }
    }

    @Override
    public void registry() {
        startTime = System.currentTimeMillis();
        FMLCommonHandler.instance().bus().register(this);
    }

    @Override
    public void unRegistry() {
        long totalTime = System.currentTimeMillis() - startTime;
        MessageUtils.sendPlayerMessage("连锁完毕; 交互数量: "+ operatorCount +"; 连锁用时: "+convertMillisToSeconds(totalTime), playerMP);
        FMLCommonHandler.instance().bus().unregister(this);
        manager.inOperate = false;
    }
}
