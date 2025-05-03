package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.Mod_Main;
import club.heiqi.qz_miner.minerModes.ModeManager;
import club.heiqi.qz_miner.statueStorage.AllPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldClient.class)
public class MixinsWorldClient {
    @Unique
    private static Logger LOG = LogManager.getLogger();

    @Inject(
        method = "sendQuittingDisconnectingPacket",
        at = @At("HEAD"),
        cancellable = true,
        remap = true
    )
    public void qz_miner$sendQuittingDisconnectingPacket(CallbackInfo ci) {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        AllPlayer allPlayerStorage = Mod_Main.allPlayerStorage;
        ModeManager manager = allPlayerStorage.allPlayer.get(player.getUniqueID());
        if (manager != null) {
            manager.unregister();
            allPlayerStorage.allPlayer.remove(player.getUniqueID());
            LOG.info("客户端管理器卸载完成");
        }
    }
}
