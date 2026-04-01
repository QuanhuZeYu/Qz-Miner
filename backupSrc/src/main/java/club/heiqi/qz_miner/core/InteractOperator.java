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
        if (!checkCanInteract()) {
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
            if (!checkCanInteract()) break;
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

    public boolean checkCanInteract() {
        if (!manager.inPressChainKey) return false;
        // 检查是否可以执行交互
        if (playerMP.getCurrentEquippedItem() != null) { // 当手上持有物品时
            // 检查是否是有耐久值的物品
            if ((playerMP.getCurrentEquippedItem().getMaxDamage() > 0 && playerMP.getCurrentEquippedItem().hasTagCompound())) {
                return playerMP.getCurrentEquippedItem().getMaxDamage() - playerMP.getCurrentEquippedItem().getItemDamage() > 1;
            }
            // 不是装备物品
            else {
                return playerMP.getCurrentEquippedItem().stackSize > 1;
            }
        }
        return true;
    }

    @Override
    public void unRegistry() {
        long totalTime = System.currentTimeMillis() - startTime;
        MessageUtils.serverSendPlayerMessage("连锁完毕; 交互数量: "+ operatorCount +"; 连锁用时: "+convertMillisToSeconds(totalTime), manager.playerUUID);
        FMLCommonHandler.instance().bus().unregister(this);
        manager.inOperate = false;
    }
}
