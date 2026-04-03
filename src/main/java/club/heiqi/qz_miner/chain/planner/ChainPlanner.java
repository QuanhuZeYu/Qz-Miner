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
 * 在服务端并行窗口中增量搜索同种方块，
 * 将确认可挖掘的点移入消费队列供执行器挖掘。
 */
public class ChainPlanner {

    private static final int MAX_SCAN_PER_SLICE = 64;

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

        visited.add(origin);
        currentFrontier.add(origin);

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
                if (currentState == null) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-state-missing");
                    return false;
                }

                if (!currentState.isChainKeyPressed()) {
                    MyMod.chainStateService.stopPlayerExecution(playerUUID, "plan-key-released");
                    return false;
                }

                currentState.updatePlannerHeartbeat();

                boolean shouldContinue = ChainSearchAlgorithm.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    target -> canHarvest((EntityPlayerMP) currentPlayer, target.getX(), target.getY(), target.getZ()),
                    target -> {
                        if (searchContext.getConfirmedCount() < searchContext.getMaxTargets()) {
                            queue.add(target);
                            searchContext.incrementConfirmedCount();
                        }
                    });

                if (currentState.getExecutionStatus() == ChainExecutionStatus.PLANNING && !queue.isEmpty()) {
                    currentState.setExecutionStatus(ChainExecutionStatus.EXECUTING, "planner-found-targets");
                    MyMod.chainStateService.syncPlayerState(playerUUID);
                }

                if (!shouldContinue) {
                    MyMod.LOG.debug("[ChainPlanner] Plan completed for player {}, confirmed={}, queuedTargets={}, pendingDrops={}",
                        playerUUID, searchContext.getConfirmedCount(), queue.size(), currentState.getPendingDrops().size());
                    currentState.setPlannerSubscription(null);
                    currentState.setExecutorWaitingForPlanner(false);
                    if (queue.isEmpty()) {
                        currentState.setExecutionStatus(ChainExecutionStatus.IDLE, "planner-completed-empty-queue");
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                return shouldContinue;
            });

        playerState.setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started chain plan for player {} at ({}, {}, {}), radius={}, maxBlocks={}",
            playerUUID, origin.getX(), origin.getY(), origin.getZ(), chainRadius, chainMaxBlocks);
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
