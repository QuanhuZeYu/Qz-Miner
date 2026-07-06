package club.heiqi.qz_miner.chain.executor;

import club.heiqi.qz_miner.MyMod;
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
        if (playerState == null || !playerState.isExecuting()) {
            return;
        }

        ChainPlayerDropBuffer dropBuffer = playerState.getDropBuffer();
        ChainDropReleaseHelper.rememberRespawnOrWorldSpawn(event.harvester, dropBuffer);
        MyMod.LOG.debug("[ChainDropCollector] HarvestDropsEvent player={} status={} rawDropStacks={} pendingBefore={}",
            event.harvester.getUniqueID(), playerState.getExecutionStatus(), event.drops.size(), dropBuffer.size());
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

        for (ChainPlayerState playerState : MyMod.chainStateService.getPlayerStates()) {
            ChainPlayerDropBuffer dropBuffer = playerState.getDropBuffer();
            if (playerState.getExecutionStatus() != ChainExecutionStatus.IDLE
                || dropBuffer.isEmpty()) {
                continue;
            }

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                if (player != null && ChainDropReleaseHelper.releaseAtRespawnOrWorldSpawn(player, dropBuffer, "world-tick-missing-player")) {
                    continue;
                }
                if (ChainDropReleaseHelper.releaseAtRememberedTarget(dropBuffer, playerState.getPlayerUUID().toString(), "world-tick-missing-player")) {
                    // 阶段8 块3：删旧 syncPlayerState（八字段同步链已删，掉落释放后无状态需同步客户端）。
                    continue;
                }
                MyMod.LOG.warn("[ChainDropCollector] Missing EntityPlayerMP for {}, keeping {} pending drop stack(s)",
                    playerState.getPlayerUUID(), dropBuffer.size());
                continue;
            }

            MyMod.LOG.debug("[ChainDropCollector] Ready to release aggregated drops for player {}, pending aggregated stacks={}",
                playerState.getPlayerUUID(), dropBuffer.size());
            if (ChainDropReleaseHelper.releaseAtPlayer((EntityPlayerMP) player, dropBuffer, "world-tick")) {
                // 阶段8 块3：删旧 syncPlayerState（八字段同步链已删，掉落释放后无状态需同步客户端）。
            }
        }
    }
}
