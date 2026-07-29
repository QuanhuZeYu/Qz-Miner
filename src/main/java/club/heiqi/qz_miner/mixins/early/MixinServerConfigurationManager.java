package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.core.PlayerManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.world.Teleporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 将玩家实例生命周期绑定到 vanilla 登录、重生和跨维度提交边界。 */
@Mixin(ServerConfigurationManager.class)
public class MixinServerConfigurationManager {

    @Inject(
        method = {
            "initializeConnectionToPlayer(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/EntityPlayerMP;Lnet/minecraft/network/NetHandlerPlayServer;)V",
            "func_72355_a(Lnet/minecraft/network/NetworkManager;Lnet/minecraft/entity/player/EntityPlayerMP;Lnet/minecraft/network/NetHandlerPlayServer;)V" },
        at = @At("RETURN"),
        require = 1,
        remap = false)
    private void onLoginCommitted(NetworkManager networkManager, EntityPlayerMP player,
            NetHandlerPlayServer handler, CallbackInfo ci) {
        PlayerManager.onVanillaLoginCommitted(player);
    }

    @Inject(
        method = "respawnPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;IZ)Lnet/minecraft/entity/player/EntityPlayerMP;",
        at = @At("HEAD"),
        require = 1)
    private void beforeRespawn(EntityPlayerMP player, int dimension, boolean conqueredEnd,
            CallbackInfoReturnable<EntityPlayerMP> cir) {
        PlayerManager.beforeVanillaRespawn(player);
    }

    @Inject(
        method = "respawnPlayer(Lnet/minecraft/entity/player/EntityPlayerMP;IZ)Lnet/minecraft/entity/player/EntityPlayerMP;",
        at = @At("RETURN"),
        require = 1)
    private void onRespawnCommitted(EntityPlayerMP player, int dimension, boolean conqueredEnd,
            CallbackInfoReturnable<EntityPlayerMP> cir) {
        PlayerManager.onVanillaRespawnCommitted(player, cir.getReturnValue());
    }

    @Inject(
        method = {
            "transferPlayerToDimension(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V",
            "func_72356_a(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V" },
        at = @At("HEAD"),
        require = 1,
        remap = false)
    private void beforeDimensionChange(EntityPlayerMP player, int dimension, Teleporter teleporter,
            CallbackInfo ci) {
        PlayerManager.beforeVanillaDimensionChange(player);
    }

    @Inject(
        method = {
            "transferPlayerToDimension(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V",
            "func_72356_a(Lnet/minecraft/entity/player/EntityPlayerMP;ILnet/minecraft/world/Teleporter;)V" },
        at = @At("RETURN"),
        require = 1,
        remap = false)
    private void onDimensionChangeCommitted(EntityPlayerMP player, int dimension, Teleporter teleporter,
            CallbackInfo ci) {
        PlayerManager.onVanillaDimensionChangeCommitted(player);
    }
}
