package club.heiqi.qz_miner.mixins.late;

import gregtech.common.blocks.TileEntityOres;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TileEntityOres.class)
public abstract class Late {
    /*@Inject(
        method = "getDrops",
        at = @At("HEAD"),
        remap = false,
        cancellable = true
    )
    private void $getDrops(Block aDroppedOre, int aFortune, CallbackInfoReturnable<ArrayList<ItemStack>> cir) {
        MY_LOG.LOG.info("测试mixins");
    }*/
}
