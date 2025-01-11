package club.heiqi.qz_miner.mixins.enderio;

import com.enderio.core.common.handlers.RightClickCropHandler;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RightClickCropHandler.class)
public class MixinRightClickCrop {

    @Inject(
        method = "handleCropRightClick",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void $handleCropRightClick(PlayerInteractEvent event, CallbackInfo ci) {
        ci.cancel();
    }
}
