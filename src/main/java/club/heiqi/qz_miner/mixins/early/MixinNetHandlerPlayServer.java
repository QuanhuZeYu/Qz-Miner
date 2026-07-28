package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.core.PlayerManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入到 NetHandlerPlayServer.onDisconnect 中，
 * 在原版注销逻辑之前按 endpoint identity 销毁玩家实例。
 */
@Mixin(value = NetHandlerPlayServer.class, remap = false)
public class MixinNetHandlerPlayServer {

    @Inject(
        method = "onDisconnect(Lnet/minecraft/util/IChatComponent;)V",
        at = @At("HEAD"),
        require = 1)
    private void onPlayerDisconnect(IChatComponent reason, CallbackInfo ci) {
        NetHandlerPlayServer self = (NetHandlerPlayServer) (Object) this;
        EntityPlayerMP player = self.playerEntity;
        if (player != null) {
            PlayerManager.onVanillaDisconnect(player, reason);
        }
    }
}
