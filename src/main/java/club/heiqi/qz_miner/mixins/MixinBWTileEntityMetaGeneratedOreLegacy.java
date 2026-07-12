package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** 调整 GTNH 2.8 BW 普通矿时运上限。 */
@Pseudo
@Mixin(targets = "bartworks.system.material.BWTileEntityMetaGeneratedOre", remap = false)
public abstract class MixinBWTileEntityMetaGeneratedOreLegacy {
    /** 将上游自然矿字段交给本模组配置裁决。 */
    @Definition(id = "natural", field = "Lbartworks/system/material/BWTileEntityMetaGeneratedOre;natural:Z")
    @Expression("this.natural")
    @ModifyExpressionValue(method = "getDrops(I)Ljava/util/ArrayList;", at = @At("MIXINEXTRAS:EXPRESSION"))
    private boolean qzMiner$treatPlacedOreAsNatural(boolean natural) {
        return FortuneCompatHelper.shouldTreatOreAsNatural(natural);
    }

    /** 拦截上游时运三级截断。 */
    @Definition(id = "fortuneLevel", local = @Local(type = int.class, argsOnly = true))
    @Expression("fortuneLevel > 3")
    @ModifyExpressionValue(method = "getDrops(I)Ljava/util/ArrayList;", at = @At("MIXINEXTRAS:EXPRESSION"))
    private boolean qzMiner$removeFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
