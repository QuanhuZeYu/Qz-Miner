package club.heiqi.qz_miner.mixins.early;

import club.heiqi.qz_miner.lifeControl.LifeController;
import cpw.mods.fml.common.FMLCommonHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FMLCommonHandler.class)
public class MixinsFMLCommonHandler {

    @Inject(
            method = "onPreServerTick",
            at = @At("HEAD"),
            cancellable = false,
            remap = false
    )
    public void qz_miner$onPreServerTick_PRE(CallbackInfo ci) {
        LifeController.forgeStartEventStart();
    }

    @Inject(
            method = "onPreServerTick",
            at = @At("TAIL"),
            cancellable = false,
            remap = false
    )
    public void qz_miner$onPreServerTick_END(CallbackInfo ci) {
        LifeController.forgeStartEventEnd();
    }
}
