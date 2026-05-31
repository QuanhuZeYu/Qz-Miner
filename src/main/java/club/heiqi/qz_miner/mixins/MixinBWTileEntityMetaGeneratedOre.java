package club.heiqi.qz_miner.mixins;

import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 调整 BW 普通矿时运限制。
 */
@Mixin(value = BWTileEntityMetaGeneratedOre.class, remap = false)
public abstract class MixinBWTileEntityMetaGeneratedOre {

    /**
     * 允许放置的 BW 普通矿也参与时运判断。
     *
     * @param tileEntity 矿石实体
     * @return 是否视为自然矿
     */
    @Redirect(
        method = "getDrops(I)Ljava/util/ArrayList;",
        at = @At(value = "FIELD", target = "Lbartworks/system/material/BWTileEntityMetaGeneratedOre;mNatural:Z", ordinal = 0))
    private boolean qzMiner$allowPlacedOreFortune(BWTileEntityMetaGeneratedOre tileEntity) {
        return FortuneCompatHelper.shouldTreatOreAsNatural(tileEntity.mNatural);
    }

    /**
     * 按配置绕过 BW 普通矿的时运 3 级截断。
     *
     * @param original 原版判断结果
     * @return 最终是否保留原版时运上限判断
     */
    @Definition(id = "fortuneLevel", local = @Local(type = int.class, argsOnly = true))
    @Expression("fortuneLevel > 3")
    @ModifyExpressionValue(
        method = "getDrops(I)Ljava/util/ArrayList;",
        at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    private boolean qzMiner$removeOreFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
