package club.heiqi.qz_miner.chain.executor;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.planner.ChainTarget;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 连锁执行器。
 *
 * 在主线程中按 Tick 消费规划结果，执行真实的方块破坏。
 */
public class ChainExecutor {

    private static final int MAX_BREAK_PER_TICK = 16;

    public ChainExecutor() {
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || MyMod.chainStateService == null || MyMod.playerManager == null) {
            return;
        }

        for (ChainPlayerState playerState : MyMod.chainStateService.getPlayerStates()) {
            if (!playerState.isExecuting()) {
                continue;
            }

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                playerState.clearRuntimeState();
                continue;
            }

            executeQueuedTargets((EntityPlayerMP) player, playerState);
        }
    }

    private void executeQueuedTargets(EntityPlayerMP player, ChainPlayerState playerState) {
        ConcurrentLinkedQueue<ChainTarget> queue = playerState.getPlannedTargets();
        int executedCount = 0;

        while (executedCount < MAX_BREAK_PER_TICK) {
            ChainTarget target = queue.poll();
            if (target == null) {
                if (playerState.getPlannerSubscription() == null) {
                    playerState.setExecuting(false);
                    MyMod.chainStateService.syncPlayerState(playerState.getPlayerUUID());
                }
                return;
            }

            if (target.getX() == (int) Math.floor(player.posX)
                && target.getY() == (int) Math.floor(player.posY) - 1
                && target.getZ() == (int) Math.floor(player.posZ)) {
                continue;
            }

            try {
                player.theItemInWorldManager.tryHarvestBlock(target.getX(), target.getY(), target.getZ());
            } catch (Exception e) {
                MyMod.LOG.error("[ChainExecutor] Failed to harvest block for player {} at ({}, {}, {})",
                    player.getUniqueID(), target.getX(), target.getY(), target.getZ(), e);
            }

            executedCount++;
        }
    }
}
