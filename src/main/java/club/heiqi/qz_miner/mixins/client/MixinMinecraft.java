package club.heiqi.qz_miner.mixins.client;

import club.heiqi.qz_miner.core.PlayerManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入到 Minecraft.loadWorld 中，
 * 当客户端退出到主菜单时通知 PlayerManager 清理所有玩家。
 */
@Mixin(value = Minecraft.class, remap = false)
public class MixinMinecraft {

    @Inject(
        method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
        at = @At("HEAD"))
    private void onExitToWorldMenu(WorldClient worldClientIn, String loadingMessage, CallbackInfo ci) {
        if (worldClientIn == null) {
            PlayerManager.clearAllPlayers();
        }
    }
}