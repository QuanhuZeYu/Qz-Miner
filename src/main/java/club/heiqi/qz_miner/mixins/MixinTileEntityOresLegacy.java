package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** 调整 GTNH 2.8 GT 普通矿时运上限。 */
@Pseudo
@Mixin(targets = "gregtech.common.blocks.TileEntityOres", remap = false)
public abstract class MixinTileEntityOresLegacy {
    /** 拦截上游时运三级截断。 */
    @Definition(id = "fortuneLevel", local = @Local(type = int.class, argsOnly = true))
    @Expression("fortuneLevel > 3")
    @ModifyExpressionValue(method = "getDrops(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;", at = @At("MIXINEXTRAS:EXPRESSION"))
    private boolean qzMiner$removeFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
