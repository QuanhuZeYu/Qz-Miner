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
import club.heiqi.qz_miner.chain.state.ChainSession;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * 当前默认的方块洪泛规划策略。
 */
public class BlockFloodFillPlanningStrategy implements ChainPlanningStrategy {

    private static final int MAX_SCAN_PER_SLICE = 64;
    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    @Override
    public boolean supports(ChainMode mode) {
        return mode == ChainMode.CHAIN;
    }

    @Override
    public void startPlanning(EntityPlayerMP player, ChainPlayerState playerState, ChainTarget origin) {
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
        ChainSession session = new ChainSession(player.getUniqueID(), playerState.getSelectedMode(), origin);
        playerState.setSession(session);
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING, "start-plan");
        session.setPlannerRunning(true);
        session.setPlannerCompleted(false);
        session.updatePlannerHeartbeat();
        session.resetExecutorThrottle();
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = session.getPendingBreakTargets();
        ConcurrentLinkedQueue<ChainTarget> currentFrontier = session.getTraversalTargets();
        ConcurrentLinkedQueue<ChainTarget> nextFrontier = new ConcurrentLinkedQueue<>();
        Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();

        visited.add(origin);

        for (int[] off : NEIGHBOR_OFFSETS) {
            ChainTarget neighbor = new ChainTarget(origin.getX() + off[0], origin.getY() + off[1], origin.getZ() + off[2]);
            if (visited.add(neighbor)) {
                Block neighborBlock = world.getBlock(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                int neighborMeta = world.getBlockMetadata(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                if (neighborBlock == sampleBlock && neighborMeta == sampleMeta) {
                    currentFrontier.add(neighbor);
                }
            }
        }

        final UUID playerUUID = player.getUniqueID();
        final ChainSearchContext searchContext = new ChainSearchContext(
            world,
            origin,
            sampleBlock,
            sampleMeta,
            Config.chainRadius,
            Config.chainMaxBlocks,
            currentFrontier,
            nextFrontier,
            visited);

        ParallelTickSubscription subscription = MyMod.ensureParallelTickExecutor().registerPre(
            "chain-plan-" + playerUUID,
            context -> {
                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-player-unavailable");
                    return false;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null || currentState.getSession() == null) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-state-missing");
                    return false;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-key-released");
                    return false;
                }

                ChainSession currentSession = currentState.getSession();
                currentSession.updatePlannerHeartbeat();

                boolean shouldContinue = ChainSearchAlgorithm.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    target -> canHarvest((EntityPlayerMP) currentPlayer, target.getX(), target.getY(), target.getZ()),
                    queue::add);

                if (!queue.isEmpty() && currentState.getExecutionStatus() == ChainExecutionStatus.PLANNING) {
                    currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "planner-found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    MyMod.LOG.debug("[ChainPlanner] Plan completed for player {}, confirmed={}, queuedTargets={}, pendingDrops={}",
                        playerUUID, searchContext.getConfirmedCount(), queue.size(), currentState.getPendingDrops().size());
                    currentSession.setPlannerSubscription(null);
                    currentSession.setPlannerRunning(false);
                    currentSession.setPlannerCompleted(true);
                    currentFrontier.clear();
                    if (queue.isEmpty()) {
                        currentState.setExecutionStatus(ChainExecutionStatus.IDLE, "planner-completed-empty-queue");
                        currentState.clearSession();
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else if (currentState.getExecutionStatus() != ChainExecutionStatus.RUNNING) {
                        currentState.setExecutionStatus(ChainExecutionStatus.RUNNING, "planner-completed-with-targets");
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    } else {
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                return shouldContinue;
            });

        session.setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started chain plan for player {} at ({}, {}, {}), radius={}, maxBlocks={}",
            playerUUID, origin.getX(), origin.getY(), origin.getZ(), Config.chainRadius, Config.chainMaxBlocks);
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
