package club.heiqi.qz_miner.chain.planner;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.Config;
import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.world.BlockEvent;

/**
 * 连锁规划器。
 *
 * 当前阶段只实现最基础的同类方块搜索，
 * 并在服务端并行 Tick 窗口中增量推进。
 */
public class ChainPlanner {

    private static final int MAX_SCAN_PER_SLICE = 32;
    private static final long EXECUTOR_HEARTBEAT_TIMEOUT_TICKS = 20L;

    public ChainPlanner() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null || !(event.getPlayer() instanceof EntityPlayerMP)) {
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) event.getPlayer();
        if (player instanceof FakePlayer || MyMod.chainStateService == null) {
            return;
        }

        ChainPlayerState playerState = MyMod.chainStateService.getOrCreatePlayerState(player.getUniqueID());
        if (!playerState.isChainKeyPressed() || playerState.isExecuting() || playerState.getSelectedMode() != ChainMode.CHAIN) {
            return;
        }

        startChainPlan(player, playerState, new ChainTarget(event.x, event.y, event.z));
    }

    private void startChainPlan(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
        World world = player.worldObj;
        Block sampleBlock = world.getBlock(origin.getX(), origin.getY(), origin.getZ());
        int sampleMeta = world.getBlockMetadata(origin.getX(), origin.getY(), origin.getZ());
        if (sampleBlock == null || sampleBlock == Blocks.air) {
            return;
        }

        if (!checkCanOperate(player, playerState)) {
            return;
        }

        playerState.clearRuntimeState("restart-plan");
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING, "start-plan");
        playerState.updatePlannerHeartbeat();
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = playerState.getPlannedTargets();
        ConcurrentLinkedQueue<ChainTarget> currentFrontier = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<>();
        Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        Set<ChainTarget> matched = ConcurrentHashMap.newKeySet();
        visited.add(origin);
        currentFrontier.add(origin);
        matched.add(origin);

        final UUID playerUUID = player.getUniqueID();
        final int chainRadius = Config.chainRadius;
        final int chainMaxBlocks = Config.chainMaxBlocks;
        final ChainSearchContext searchContext = new ChainSearchContext(
            world,
            origin,
            sampleBlock,
            sampleMeta,
            chainRadius,
            chainMaxBlocks,
            currentFrontier,
            nextFrontier,
            visited,
            matched);

        ParallelTickSubscription subscription = MyMod.ensureParallelTickExecutor().registerPre(
            "chain-plan-" + playerUUID,
            context -> {
                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    MyMod.LOG.debug("[ChainPlanner] Stopping plan for player {} because current player is unavailable, matchedTargets={}, queuedTargets={}",
                        playerUUID, matched.size(), queue.size());
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-player-unavailable");
                    return false;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null) {
                    MyMod.LOG.debug("[ChainPlanner] Stopping plan for player {} because state is missing, matchedTargets={}, queuedTargets={}",
                        playerUUID, matched.size(), queue.size());
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-state-missing");
                    return false;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.LOG.debug("[ChainPlanner] Stopping plan for player {} because chain key was released, matchedTargets={}, queuedTargets={}",
                        playerUUID, matched.size(), queue.size());
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-key-released");
                    return false;
                }

                if (isExecutorHeartbeatTimedOut(currentState, (EntityPlayerMP) currentPlayer)) {
                    MyMod.LOG.debug("[ChainPlanner] Stopping plan for player {} because executor heartbeat timed out, matchedTargets={}, queuedTargets={}, lastExecutorTick={}",
                        playerUUID, matched.size(), queue.size(), currentState.getExecutorHeartbeatTick());
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "executor-heartbeat-timeout");
                    return false;
                }

                currentState.updatePlannerHeartbeat();
                MyMod.LOG.debug("[ChainPlanner] Slice tick player={} status={} frontier={} matched={} queuedTargets={} visited={} pendingDrops={}", 
                    playerUUID,
                    currentState.getExecutionStatus(),
                    searchContext.getCurrentFrontier().size(),
                    matched.size(),
                    queue.size(),
                    visited.size(),
                    currentState.getPendingDrops().size());

                boolean shouldContinue = ChainSearchAlgorithm.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    target -> canHarvest((EntityPlayerMP) currentPlayer, target.getX(), target.getY(), target.getZ()),
                    target -> {
                        if (canHarvest((EntityPlayerMP) currentPlayer, target.getX(), target.getY(), target.getZ())) {
                            queue.add(target);
                        }
                    });

                if (currentState.getExecutionStatus() == ChainExecutionStatus.PLANNING && queue.size() > 1) {
                    MyMod.LOG.debug("[ChainPlanner] Switching player {} from PLANNING to EXECUTING, queuedTargets={}, matchedTargets={}",
                        playerUUID, queue.size(), matched.size());
                    currentState.setExecutionStatus(ChainExecutionStatus.EXECUTING, "planner-found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    ChainPlayerState finalState = MyMod.chainStateService.getPlayerState(playerUUID);
                    if (finalState != null) {
                        MyMod.LOG.debug("[ChainPlanner] Plan completed for player {}, matchedTargets={}, queuedTargets={}, pendingDrops={}, status={}",
                            playerUUID, matched.size(), finalState.getPlannedTargets().size(), finalState.getPendingDrops().size(), finalState.getExecutionStatus());
                        finalState.setPlannerSubscription(null);
                        finalState.setExecutorWaitingForPlanner(false);
                        if (finalState.getPlannedTargets().isEmpty()) {
                            MyMod.LOG.debug("[ChainPlanner] Plan completed with empty queue for player {}, switching to IDLE immediately", playerUUID);
                            finalState.setExecutionStatus(ChainExecutionStatus.IDLE, "planner-completed-empty-queue");
                            MyMod.chainStateService.syncPlayerState(playerUUID);
                        } else {
                            if (finalState.getExecutionStatus() != ChainExecutionStatus.EXECUTING) {
                                MyMod.LOG.debug("[ChainPlanner] Switching player {} from PLANNING to EXECUTING after plan completion, queuedTargets={}, matchedTargets={}",
                                    playerUUID, finalState.getPlannedTargets().size(), matched.size());
                                finalState.setExecutionStatus(ChainExecutionStatus.EXECUTING, "planner-completed-with-queue");
                                MyMod.chainStateService.syncPlayerState(playerUUID);
                            }
                        }
                    }
                }
                return shouldContinue;
            });

        playerState.setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started chain plan for player {} at ({}, {}, {}), radius={}, maxBlocks={}",
            playerUUID, origin.getX(), origin.getY(), origin.getZ(), chainRadius, chainMaxBlocks);
    }

    private boolean isExecutorHeartbeatTimedOut(ChainPlayerState playerState, EntityPlayerMP player) {
        if (playerState.getExecutionStatus() != ChainExecutionStatus.EXECUTING) {
            return false;
        }

        long executorHeartbeatTick = playerState.getExecutorHeartbeatTick();
        if (executorHeartbeatTick <= 0L) {
            return false;
        }

        long currentTick = player.worldObj.getTotalWorldTime();
        return currentTick - executorHeartbeatTick > EXECUTOR_HEARTBEAT_TIMEOUT_TICKS;
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

    private boolean canHarvest(EntityPlayerMP player, int x, int y, int z) {
        Block block = player.worldObj.getBlock(x, y, z);
        if (block == null || block == Blocks.air || block == Blocks.bedrock || block.getMaterial().isLiquid()) {
            return false;
        }

        int meta = player.worldObj.getBlockMetadata(x, y, z);
        if (player.capabilities.isCreativeMode) {
            return true;
        }
        return block.canHarvestBlock(player, meta);
    }

}
