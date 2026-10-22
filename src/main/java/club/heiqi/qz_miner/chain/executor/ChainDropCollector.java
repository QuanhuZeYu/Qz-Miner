package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.eventbus.ChainTickSource;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerDropBuffer;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.BlockEvent;

/**
 * 连锁掉落聚合器。
 */
public class ChainDropCollector {

    /** 达阈值后延后重试并输出摘要，失败次数不授权丢弃资产。 */
    private static final int DROP_RELEASE_FAST_RETRIES = 100;
    private static final long DROP_RELEASE_RETRY_INTERVAL_TICKS = 100L;

    public ChainDropCollector() {
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onHarvestDrops(BlockEvent.HarvestDropsEvent event) {
        if (!(event.harvester instanceof EntityPlayerMP) || MyMod.chainStateService == null) {
            return;
        }

        ChainPlayerState playerState = MyMod.chainStateService.getPlayerState(event.harvester.getUniqueID());
        if (playerState == null) {
            return;
        }
        boolean executing = playerState.isExecuting();
        // 起点方块掉落捕获：原版 tryHarvestBlock 同 tick 同步触发 HarvestDropsEvent 早于 collector 执行窗口，
        // armed 一次后此处也收；consume 必须只调一次（一次性语义）。
        boolean seedArmed = playerState.consumeSeedDropCaptureIfArmed(ChainTickSource.currentServerTick());
        if (!executing && !seedArmed) {
            return;
        }

        ChainPlayerDropBuffer dropBuffer = playerState.getDropBuffer();
        ChainDropReleaseHelper.rememberRespawnOrWorldSpawn(event.harvester, dropBuffer);
        MyMod.LOG.debug("[ChainDropCollector] HarvestDropsEvent player={} status={} executing={} seedArmed={} rawDropStacks={} pendingBefore={}",
            event.harvester.getUniqueID(), playerState.getExecutionStatus(), executing, seedArmed, event.drops.size(), dropBuffer.size());
        dropBuffer.addAll(event.drops);
        MyMod.LOG.debug("[ChainDropCollector] HarvestDropsEvent merged player={} pendingAfter={}",
            event.harvester.getUniqueID(), dropBuffer.size());
        event.drops.clear();
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.START
            || event.world == null
            || event.world.isRemote
            || MyMod.chainStateService == null
            || MyMod.playerManager == null) {
            return;
        }

        long serverTick = Math.max(0L, ChainTickSource.currentServerTick());
        for (ChainPlayerState playerState : MyMod.chainStateService.getPlayerStates()) {
            ChainPlayerDropBuffer dropBuffer = playerState.getDropBuffer();
            if (dropBuffer.isEmpty()) {
                MyMod.chainStateService.removeDeliveredDropOwner(playerState);
                continue;
            }
            if (playerState.getExecutionStatus() != ChainExecutionStatus.IDLE
                    || !playerState.isDropReleaseReady(serverTick)) {
                continue;
            }

            // 一个服务端 tick 只尝试一次，多个维度的 WorldTick 不得放大重试和日志。
            playerState.deferDropReleaseUntil(serverTick + 1L);
            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            boolean released = false;
            try {
                released = releaseDrops(player, playerState);
            } catch (RuntimeException e) {
                MyMod.LOG.debug("[ChainDropCollector] Drop release context failed for player {}, keeping pending assets",
                        playerState.getPlayerUUID(), e);
            }
            if (released) {
                playerState.resetDropReleaseFailure();
                MyMod.chainStateService.removeDeliveredDropOwner(playerState);
                continue;
            }

            int failures = playerState.incrementDropReleaseFailure();
            if (failures >= DROP_RELEASE_FAST_RETRIES) {
                playerState.deferDropReleaseUntil(serverTick + DROP_RELEASE_RETRY_INTERVAL_TICKS);
            }
            if (failures == 1 || failures >= DROP_RELEASE_FAST_RETRIES) {
                MyMod.LOG.warn("[ChainDropCollector] Keeping {} pending drop stack(s) for player {} after {} failed release attempt(s)",
                        dropBuffer.size(), playerState.getPlayerUUID(), failures);
            }
        }
    }

    /** 当前位置、重生/出生点、已记忆位置依次尝试；未生成部分由 helper 回填原 buffer。 */
    private boolean releaseDrops(EntityPlayer player, ChainPlayerState state) {
        ChainPlayerDropBuffer buffer = state.getDropBuffer();
        if (player instanceof EntityPlayerMP
                && ChainDropReleaseHelper.releaseAtPlayer((EntityPlayerMP) player, buffer, "world-tick")) {
            return true;
        }
        if (player != null
                && ChainDropReleaseHelper.releaseAtRespawnOrWorldSpawn(player, buffer, "world-tick-respawn")) {
            return true;
        }
        return ChainDropReleaseHelper.releaseAtRememberedTarget(
                buffer, state.getPlayerUUID().toString(), "world-tick-remembered");
    }
}
