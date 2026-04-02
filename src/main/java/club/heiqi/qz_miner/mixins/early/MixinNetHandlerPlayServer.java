package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.event.PlayerDisconnectEvent;
import club.heiqi.qz_miner.event.QzEvents;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入到 NetHandlerPlayServer.onDisconnect 中，
 * 在原版注销逻辑之前触发 PlayerDisconnectEvent。
 */
@Mixin(NetHandlerPlayServer.class)
public class MixinNetHandlerPlayServer {

    @Inject(
        method = "onDisconnect(Lnet/minecraft/util/IChatComponent;)V",
        at = @At("HEAD"))
    private void onPlayerDisconnect(IChatComponent reason, CallbackInfo ci) {
        NetHandlerPlayServer self = (NetHandlerPlayServer) (Object) this;
        EntityPlayerMP player = self.playerEntity;
        if (player != null) {
            QzEvents.post(new PlayerDisconnectEvent(player, reason));
        }
    }
}