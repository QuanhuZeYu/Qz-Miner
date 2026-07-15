package club.heiqi.qz_miner.mixins.client;

import club.heiqi.qz_miner.client.toolswap.AutoToolSwapHooks;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 捕获客户端原版真正完成方块破坏的最早返回信号。 */
@Mixin(value = PlayerControllerMP.class, remap = false)
public abstract class MixinPlayerControllerMPToolSwap {

    /** 仅 true 返回值代表本地成功破坏；左键/C07 不作为冻结信号。 */
    @Inject(method = "onPlayerDestroyBlock(IIII)Z", at = @At("RETURN"))
    private void qzMiner$afterPlayerDestroyBlock(
            int x, int y, int z, int side, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) {
            AutoToolSwapHooks.onLocalBlockDestroyed();
        }
    }
}
