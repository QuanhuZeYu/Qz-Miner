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
 * 在主线程中按 Tick 消费规划结果，执行真实的方块破坏。
 */
public class ChainExecutor {

    private static final long PLANNER_HEARTBEAT_TIMEOUT_MILLIS = 2000L;

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

            MyMod.LOG.debug("[ChainExecutor] Tick check player={} status={} queuedTargets={} pendingDrops={} plannerHeartbeat={} executorHeartbeat={} waitingForPlanner={}",
                playerState.getPlayerUUID(),
                playerState.getExecutionStatus(),
                playerState.getPlannedTargets().size(),
                playerState.getPendingDrops().size(),
                playerState.getPlannerHeartbeatMillis(),
                playerState.getExecutorHeartbeatTick(),
                playerState.isExecutorWaitingForPlanner());

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                MyMod.LOG.debug("[ChainExecutor] Stopping execution for player {} because server player is unavailable, status={}, queuedTargets={}, pendingDrops={}",
                    playerState.getPlayerUUID(), playerState.getExecutionStatus(), playerState.getPlannedTargets().size(), playerState.getPendingDrops().size());
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "player-unavailable");
                continue;
            }

            playerState.updateExecutorHeartbeat(((EntityPlayerMP) player).worldObj.getTotalWorldTime());

            executeQueuedTargets((EntityPlayerMP) player, playerState);
        }
    }

    private void executeQueuedTargets(EntityPlayerMP player, ChainPlayerState playerState) {
        ConcurrentLinkedQueue<ChainTarget> queue = playerState.getPlannedTargets();
        int maxBreakPerTick = Config.maxBreakPerTick;
        int executedCount = 0;

        while (executedCount < maxBreakPerTick) {
            if (!checkCanOperate(player, playerState)) {
                MyMod.LOG.debug("[ChainExecutor] Stopping execution for player {} because checkCanOperate failed, status={}, queuedTargets={}, pendingDrops={}",
                    player.getUniqueID(), playerState.getExecutionStatus(), queue.size(), playerState.getPendingDrops().size());
                MyMod.chainStateService.stopPlayerExecution(playerState.getPlayerUUID(), "check-can-operate-failed");
                return;
            }

            ChainTarget target = queue.poll();
            if (target == null) {
                if (!isPlannerAlive(playerState)) {
                    MyMod.LOG.debug("[ChainExecutor] Execution queue drained for player {}, switching to IDLE, pendingDrops={}",
                        player.getUniqueID(), playerState.getPendingDrops().size());
                    playerState.setExecutorWaitingForPlanner(false);
                    playerState.setExecutionStatus(ChainExecutionStatus.IDLE, "executor-queue-drained-planner-not-alive");
                    MyMod.chainStateService.syncPlayerState(playerState.getPlayerUUID());
                } else if (!playerState.isExecutorWaitingForPlanner()) {
                    playerState.setExecutorWaitingForPlanner(true);
                    MyMod.LOG.debug("[ChainExecutor] Execution queue empty for player {}, waiting for planner, pendingDrops={}",
                        player.getUniqueID(), playerState.getPendingDrops().size());
                }
                return;
            }

            if (playerState.isExecutorWaitingForPlanner()) {
                playerState.setExecutorWaitingForPlanner(false);
                MyMod.LOG.debug("[ChainExecutor] Resumed execution for player {} after planner supplied more targets, remainingQueuedTargets={}",
                    player.getUniqueID(), queue.size() + 1);
            }

            if (target.getX() == (int) Math.floor(player.posX)
                && target.getY() == (int) Math.floor(player.posY) - 1
                && target.getZ() == (int) Math.floor(player.posZ)) {
                continue;
            }

            try {
                MyMod.LOG.debug("[ChainExecutor] Harvesting block for player {} at ({}, {}, {}), remainingQueuedBeforePoll={}",
                    player.getUniqueID(), target.getX(), target.getY(), target.getZ(), queue.size());
                player.theItemInWorldManager.tryHarvestBlock(target.getX(), target.getY(), target.getZ());
            } catch (Exception e) {
                MyMod.LOG.error("[ChainExecutor] Failed to harvest block for player {} at ({}, {}, {})",
                    player.getUniqueID(), target.getX(), target.getY(), target.getZ(), e);
            }

            executedCount++;
        }
    }

    private boolean isPlannerAlive(ChainPlayerState playerState) {
        if (playerState.getPlannerSubscription() == null) {
            return false;
        }

        long heartbeat = playerState.getPlannerHeartbeatMillis();
        if (heartbeat <= 0L) {
            return true;
        }

        return System.currentTimeMillis() - heartbeat <= PLANNER_HEARTBEAT_TIMEOUT_MILLIS;
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
