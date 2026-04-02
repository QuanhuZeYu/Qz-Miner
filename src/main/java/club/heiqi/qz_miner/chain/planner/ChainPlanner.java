package club.heiqi.qz_miner.chain.planner;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    private static final int MAX_CHAIN_BLOCKS = 256;
    private static final int MAX_SCAN_PER_SLICE = 32;
    private static final int MAX_RADIUS = 4;

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

        playerState.clearRuntimeState();
        playerState.setExecutionStatus(ChainExecutionStatus.PLANNING);
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = playerState.getPlannedTargets();
        ConcurrentLinkedQueue<ChainTarget> frontier = new ConcurrentLinkedQueue<>();
        Set<ChainTarget> visited = ConcurrentHashMap.newKeySet();
        Set<ChainTarget> matched = ConcurrentHashMap.newKeySet();
        visited.add(origin);
        frontier.add(origin);
        matched.add(origin);
        queue.add(origin);

        final UUID playerUUID = player.getUniqueID();
        final ChainSearchContext searchContext = new ChainSearchContext(
            world,
            origin,
            sampleBlock,
            sampleMeta,
            MAX_RADIUS,
            MAX_CHAIN_BLOCKS,
            frontier,
            visited,
            matched);

        ParallelTickSubscription subscription = MyMod.parallelTickExecutor.registerPre(
            "chain-plan-" + playerUUID,
            context -> {
                EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                if (!(currentPlayer instanceof EntityPlayerMP)) {
                    return false;
                }

                ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                if (currentState == null || !checkCanOperate((EntityPlayerMP) currentPlayer, currentState)) {
                    return false;
                }

                int beforeMatched = matched.size();
                boolean shouldContinue = ChainSearchAlgorithm.step(
                    searchContext,
                    MAX_SCAN_PER_SLICE,
                    target -> canHarvest((EntityPlayerMP) currentPlayer, target.getX(), target.getY(), target.getZ()));

                if (matched.size() > beforeMatched) {
                    for (ChainTarget matchedTarget : matched) {
                        if (!queue.contains(matchedTarget)) {
                            queue.add(matchedTarget);
                        }
                    }

                    if (currentState.getExecutionStatus() == ChainExecutionStatus.PLANNING && queue.size() > 1) {
                        currentState.setExecutionStatus(ChainExecutionStatus.EXECUTING);
                        MyMod.chainStateService.syncPlayerState(playerUUID);
                    }
                }

                if (!shouldContinue) {
                    ChainPlayerState finalState = MyMod.chainStateService.getPlayerState(playerUUID);
                    if (finalState != null) {
                        finalState.setPlannerSubscription(null);
                        if (finalState.getPlannedTargets().isEmpty()) {
                            finalState.setExecutionStatus(ChainExecutionStatus.IDLE);
                            MyMod.chainStateService.syncPlayerState(playerUUID);
                        }
                    }
                }
                return shouldContinue;
            });

        playerState.setPlannerSubscription(subscription);
        MyMod.LOG.debug("[ChainPlanner] Started chain plan for player {} at ({}, {}, {})",
            playerUUID, origin.getX(), origin.getY(), origin.getZ());
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
