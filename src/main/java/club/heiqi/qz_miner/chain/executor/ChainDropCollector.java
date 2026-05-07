package club.heiqi.qz_miner.chain.executor;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
import club.heiqi.qz_miner.chain.state.ChainSession;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
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
        ChainSession session = playerState == null ? null : playerState.getSession();
        if (playerState == null || !playerState.isExecuting() || session == null) {
            return;
        }

        MyMod.LOG.debug("[ChainDropCollector] HarvestDropsEvent player={} status={} rawDropStacks={} pendingBefore={}",
            event.harvester.getUniqueID(), playerState.getExecutionStatus(), event.drops.size(), session.getRuntimeState().getPendingDrops().size());
        for (ItemStack drop : event.drops) {
            mergeDrop(session.getRuntimeState().getPendingDrops(), drop);
        }
        MyMod.LOG.debug("[ChainDropCollector] HarvestDropsEvent merged player={} pendingAfter={}",
            event.harvester.getUniqueID(), session.getRuntimeState().getPendingDrops().size());
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
            ChainSession session = playerState.getSession();
            if (playerState.getExecutionStatus() != ChainExecutionStatus.IDLE
                || session == null
                || session.getRuntimeState().getPendingDrops().isEmpty()) {
                continue;
            }

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                MyMod.LOG.warn("[ChainDropCollector] Missing EntityPlayerMP for {}, keeping {} pending drop stack(s)",
                    playerState.getPlayerUUID(), session.getRuntimeState().getPendingDrops().size());
                continue;
            }

            MyMod.LOG.debug("[ChainDropCollector] Ready to release aggregated drops for player {}, pending aggregated stacks={}",
                playerState.getPlayerUUID(), session.getRuntimeState().getPendingDrops().size());
            releaseDrops((EntityPlayerMP) player, session);
            if (playerState.isSessionActive(session)
                && session.getRuntimeState().getPendingBreakTargets().isEmpty()
                && !session.getRuntimeState().isPlannerRunning()) {
                playerState.clearSession();
                MyMod.chainStateService.syncPlayerState(playerState.getPlayerUUID());
            }
        }
    }

    private void releaseDrops(EntityPlayerMP player, ChainSession session) {
        List<ItemStack> drops = new ArrayList<>(session.getRuntimeState().getPendingDrops());
        session.getRuntimeState().getPendingDrops().clear();
        MyMod.LOG.debug("[ChainDropCollector] Releasing {} aggregated drop stack(s) for player {}",
            drops.size(), player.getUniqueID());
        for (ItemStack itemStack : drops) {
            player.worldObj.spawnEntityInWorld(new EntityItem(player.worldObj, player.posX, player.posY, player.posZ, itemStack));
        }
    }

    private void mergeDrop(List<ItemStack> drops, ItemStack incoming) {
        for (ItemStack existing : drops) {
            if (existing.isItemEqual(incoming)
                && ItemStack.areItemStackTagsEqual(existing, incoming)) {
                existing.stackSize += incoming.stackSize;
                return;
            }
        }
        drops.add(incoming.copy());
    }
}
