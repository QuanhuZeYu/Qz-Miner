package club.heiqi.qz_miner.chain.executor;

import java.util.ArrayList;
import java.util.List;

import club.heiqi.qz_miner.MyMod;
import club.heiqi.qz_miner.chain.state.ChainExecutionStatus;
import club.heiqi.qz_miner.chain.state.ChainPlayerState;
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
    }

    @SubscribeEvent
    public void onHarvestDrops(BlockEvent.HarvestDropsEvent event) {
        if (!(event.harvester instanceof EntityPlayerMP) || MyMod.chainStateService == null) {
            return;
        }

        ChainPlayerState playerState = MyMod.chainStateService.getPlayerState(event.harvester.getUniqueID());
        if (playerState == null || playerState.getExecutionStatus() != ChainExecutionStatus.EXECUTING) {
            return;
        }

        for (ItemStack drop : event.drops) {
            mergeDrop(playerState.getPendingDrops(), drop);
        }
        event.drops.clear();
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.START || MyMod.chainStateService == null || MyMod.playerManager == null) {
            return;
        }

        for (ChainPlayerState playerState : MyMod.chainStateService.getPlayerStates()) {
            if (playerState.getExecutionStatus() != ChainExecutionStatus.IDLE || playerState.getPendingDrops().isEmpty()) {
                continue;
            }

            EntityPlayer player = MyMod.playerManager.getPlayer(playerState.getPlayerUUID());
            if (!(player instanceof EntityPlayerMP)) {
                playerState.getPendingDrops().clear();
                continue;
            }

            releaseDrops((EntityPlayerMP) player, playerState);
        }
    }

    private void releaseDrops(EntityPlayerMP player, ChainPlayerState playerState) {
        List<ItemStack> drops = new ArrayList<>(playerState.getPendingDrops());
        playerState.getPendingDrops().clear();
        for (ItemStack itemStack : drops) {
            player.worldObj.spawnEntityInWorld(new EntityItem(player.worldObj, player.posX, player.posY, player.posZ, itemStack));
        }
    }

    private void mergeDrop(List<ItemStack> drops, ItemStack incoming) {
        for (ItemStack existing : drops) {
            if (ItemStack.areItemStacksEqual(existing, incoming)
                && ItemStack.areItemStackTagsEqual(existing, incoming)) {
                existing.stackSize += incoming.stackSize;
                return;
            }
        }
        drops.add(incoming.copy());
    }
}
