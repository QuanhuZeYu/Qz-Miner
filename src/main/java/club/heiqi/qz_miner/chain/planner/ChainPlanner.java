package club.heiqi.qz_miner.chain.planner;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.mode.ChainMode;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.parallel.ParallelTickSubscription;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
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

        playerState.clearRuntimeState();
        playerState.setExecuting(true);
        MyMod.chainStateService.syncPlayerState(player.getUniqueID());

        ConcurrentLinkedQueue<ChainTarget> queue = playerState.getPlannedTargets();
        Set<ChainTarget> visited = new HashSet<>();
        visited.add(origin);
        queue.add(origin);

        final int diameter = MAX_RADIUS * 2 + 1;
        final int totalSize = diameter * diameter * diameter;
        final UUID playerUUID = player.getUniqueID();
        final int[] index = new int[] {0};

        ParallelTickSubscription subscription = MyMod.parallelTickExecutor.registerPre(
            "chain-plan-" + playerUUID,
            context -> {
                int processed = 0;
                while (context.hasTimeLeft() && processed < MAX_SCAN_PER_SLICE && index[0] < totalSize && visited.size() < MAX_CHAIN_BLOCKS) {
                    EntityPlayer currentPlayer = MyMod.playerManager == null ? null : MyMod.playerManager.getPlayer(playerUUID);
                    if (!(currentPlayer instanceof EntityPlayerMP)) {
                        return false;
                    }

                    ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                    if (currentState == null || !currentState.isChainKeyPressed()) {
                        return false;
                    }

                    int currentIndex = index[0]++;
                    int localX = currentIndex % diameter;
                    int localY = (currentIndex / diameter) % diameter;
                    int localZ = currentIndex / (diameter * diameter);

                    int worldX = origin.getX() + localX - MAX_RADIUS;
                    int worldY = origin.getY() + localY - MAX_RADIUS;
                    int worldZ = origin.getZ() + localZ - MAX_RADIUS;

                    ChainTarget target = new ChainTarget(worldX, worldY, worldZ);
                    if (!visited.add(target)) {
                        processed++;
                        continue;
                    }

                    Block block = world.getBlock(worldX, worldY, worldZ);
                    int meta = world.getBlockMetadata(worldX, worldY, worldZ);
                    if (block == sampleBlock && meta == sampleMeta && canHarvest((EntityPlayerMP) currentPlayer, worldX, worldY, worldZ)) {
                        queue.add(target);
                    }

                    processed++;
                }

                boolean shouldContinue = index[0] < totalSize && visited.size() < MAX_CHAIN_BLOCKS;
                if (!shouldContinue) {
                    ChainPlayerState currentState = MyMod.chainStateService.getPlayerState(playerUUID);
                    if (currentState != null) {
                        currentState.setPlannerSubscription(null);
                        if (currentState.getPlannedTargets().isEmpty()) {
                            currentState.setExecuting(false);
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
}
