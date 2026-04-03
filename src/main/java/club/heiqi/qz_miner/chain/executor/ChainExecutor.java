package club.heiqi.qz_miner.chain.executor;

import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

/**
 * 连锁执行器。
 *
 * 每 tick 做两件事：
 * 1. 检查是否应该停止（按键松开、工具损坏等）
 * 2. 从队列中消费一个点并挖掘
 *
 * 无论队列是否为空，都应该继续执行这两件事。
 */
public class ChainExecutor {

    public ChainExecutor() {
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || MyMod.chainStateService == null || MyMod.playerManager == null) {
            return;
        }

        for (ChainPlayerState playerState : MyMod.chainStateService.getPlayerStates()) {
            if (playerState.getExecutionStatus() != ChainExecutionStatus.EXECUTING) {
                continue;
            }

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "player-unavailable");
                continue;
            }

            if (!checkCanOperate((EntityPlayerMP) player, playerState)) {
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "check-can-operate-failed");
                continue;
            }

            ConcurrentLinkedQueue<ChainTarget> queue = playerState.getPlannedTargets();
            int maxBreakPerTick = Config.maxBreakPerTick;
            int executedCount = 0;

            while (executedCount < maxBreakPerTick) {
                ChainTarget target = queue.poll();
                if (target == null) {
                    break;
                }

                if (target.getX() == (int) Math.floor(player.posX)
                    && target.getY() == (int) Math.floor(player.posY) - 1
                    && target.getZ() == (int) Math.floor(player.posZ)) {
                    continue;
                }

                try {
                    ((EntityPlayerMP) player).theItemInWorldManager.tryHarvestBlock(target.getX(), target.getY(), target.getZ());
                } catch (Exception e) {
                    MyMod.LOG.error("[ChainExecutor] Failed to harvest block for player {} at ({}, {}, {})",
                        player.getUniqueID(), target.getX(), target.getY(), target.getZ(), e);
                }

                executedCount++;
            }

            playerState.updateExecutorHeartbeat(((EntityPlayerMP) player).worldObj.getTotalWorldTime());
        }
    }

    private boolean checkCanOperate(EntityPlayerMP player, ChainPlayerState playerState) {
        if (!playerState.isChainKeyPressed()) {
            return false;
        }

        ItemStack equippedItem = player.getCurrentEquippedItem();
        if (equippedItem != null && equippedItem.isItemStackDamageable()) {
            return equippedItem.getMaxDamage() - equippedItem.getItemDamage() > 1;
        }

        return true;
    }
}
