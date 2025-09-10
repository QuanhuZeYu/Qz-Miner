package club.heiqi.qz_miner.core;

import club.heiqi.qz_miner.utils.MessageUtils;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import org.joml.Vector3d;
import org.joml.Vector3i;

public class InteractOperator extends BaseOperator {
    public InteractOperator(Vector3i pos, Manager manager) {
        super(pos, manager);
    }

    @Override
    @SubscribeEvent
    public void operatorTask(TickEvent.ServerTickEvent event) {
        // 没按下连锁键    注销
        // 物品只有一个时   注销
        // 物品耐久只剩1时  注销
        if (!manager.inPressChainKey ||
                (playerMP.getCurrentEquippedItem() != null && playerMP.getCurrentEquippedItem().stackSize == 1) ||
                (playerMP.getCurrentEquippedItem() != null && playerMP.getCurrentEquippedItem().getMaxDamage() - playerMP.getCurrentEquippedItem().getItemDamage() == 1)
        ) {
            this.unRegistry();
            return;
        }

        if (canBreakPositions.isEmpty()) {
            return;
        }

        // 记录玩家位置以便还原
        Vector3d playerPos = new Vector3d(playerMP.posX, playerMP.posY, playerMP.posZ);

        int breakCountInTick = 0;
        Vector3i pos;
        playerMP.rotationPitch = 90;
        while ((pos = canBreakPositions.poll()) != null) {
            // 检查是否可以执行交互
            if ((playerMP.getCurrentEquippedItem() != null && playerMP.getCurrentEquippedItem().stackSize == 1) ||
                    (playerMP.getCurrentEquippedItem() != null && playerMP.getCurrentEquippedItem().getMaxDamage() - playerMP.getCurrentEquippedItem().getItemDamage() == 1)
            ) {
                break;
            }
            // 将玩家位置设置到该方块位置
            playerMP.posX = pos.x; playerMP.posY = pos.y; playerMP.posZ = pos.z;

            playerMP.theItemInWorldManager.activateBlockOrUseItem(
                    playerMP, playerMP.worldObj,
                    playerMP.getCurrentEquippedItem(),
                    pos.x, pos.y, pos.z,
                    manager.hitSide,
                    0,0,0);
            if (playerMP.getCurrentEquippedItem() != null) {
                playerMP.theItemInWorldManager.tryUseItem(playerMP, playerMP.worldObj, playerMP.getCurrentEquippedItem());
            }

            breakCountInTick++;
            operatorCount++;
            if (breakCountInTick >= 64) {
                // 还原玩家位置
                playerMP.posX = playerPos.x; playerMP.posY = playerPos.y; playerMP.posZ = playerPos.z;
                return;
            }
        }

        // 还原玩家位置
        playerMP.posX = playerPos.x; playerMP.posY = playerPos.y; playerMP.posZ = playerPos.z;

        if (positionFounder.stopped.get()) {
            this.unRegistry();
        }
    }

    @Override
    public void unRegistry() {
        long totalTime = System.currentTimeMillis() - startTime;
        MessageUtils.sendPlayerMessage("连锁完毕; 交互数量: "+ operatorCount +"; 连锁用时: "+convertMillisToSeconds(totalTime), playerMP);
        FMLCommonHandler.instance().bus().unregister(this);
        manager.inOperate = false;
    }
}
