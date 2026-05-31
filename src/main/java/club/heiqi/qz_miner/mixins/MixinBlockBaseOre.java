package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import gtPlusPlus.core.block.base.BlockBaseOre;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 调整 GT++ 普通矿时运上限。
 */
@Mixin(value = BlockBaseOre.class, remap = false)
public abstract class MixinBlockBaseOre {

    /**
     * 按配置绕过 GT++ 普通矿的时运 3 级截断。
     *
     * @param original 原版判断结果
     * @return 最终是否保留原版时运上限判断
     */
    @Definition(id = "fortuneLevel", local = @Local(type = int.class, argsOnly = true, ordinal = 4))
    @Expression("fortuneLevel > 3")
    @ModifyExpressionValue(
        method = "getDrops(Lnet/minecraft/world/World;IIIII)Ljava/util/ArrayList;",
        at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    private boolean qzMiner$removeOreFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
