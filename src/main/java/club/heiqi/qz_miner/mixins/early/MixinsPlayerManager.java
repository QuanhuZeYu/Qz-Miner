package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.util.BroadCastMessage;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.WorldProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerManager.class)
public class MixinsPlayerManager {

    @Inject(
        method = "updatePlayerInstances",
        at = @At("HEAD"),
        cancellable = true,
        remap = true
    )
    public void qz_miner$updatePlayerInstances(CallbackInfo ci) {
        ci.cancel();
        long i = ((PlayerManager)((Object)this)).theWorldServer.getTotalWorldTime();
        int j;

        PlayerManager.PlayerInstance playerinstance;

        if (i - ((PlayerManager)((Object)this)).previousTotalWorldTime > 8000L)
        {
            ((PlayerManager)((Object)this)).previousTotalWorldTime = i;

            for (j = 0; j < ((PlayerManager)((Object)this)).playerInstanceList.size(); ++j)
            {
                playerinstance = (PlayerManager.PlayerInstance)((PlayerManager)((Object)this)).playerInstanceList.get(j);
                if (playerinstance != null) {
                    playerinstance.sendChunkUpdate();
                    playerinstance.processChunk();
                }
                else {
                    BroadCastMessage.broadCastMessage("qz_Miner已阻止 playerinstance 的 NPE 异常");
                }
            }
        }
        else
        {
            for (j = 0; j < ((PlayerManager)((Object)this)).chunkWatcherWithPlayers.size(); ++j)
            {
                playerinstance = (PlayerManager.PlayerInstance)((PlayerManager)((Object)this)).chunkWatcherWithPlayers.get(j);
                if (playerinstance != null) {
                    playerinstance.sendChunkUpdate();
                }
                else {
                    BroadCastMessage.broadCastMessage("qz_Miner已阻止 playerinstance 的 NPE 异常");
                }
            }
        }

        ((PlayerManager)((Object)this)).chunkWatcherWithPlayers.clear();

        if (((PlayerManager)((Object)this)).players.isEmpty())
        {
            WorldProvider worldprovider = ((PlayerManager)((Object)this)).theWorldServer.provider;

            if (!worldprovider.canRespawnHere())
            {
                ((PlayerManager)((Object)this)).theWorldServer.theChunkProviderServer.unloadAllChunks();
            }
        }
    }
}
