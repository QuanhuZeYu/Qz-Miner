package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import gregtech.common.ores.GTOreAdapter;
import gregtech.common.ores.OreInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 调整 GT 普通矿时运限制。
 */
@Mixin(value = GTOreAdapter.class, remap = false)
public abstract class MixinGTOreAdapter {

    /**
     * 允许放置的 GT 矿也参与时运判断。
     *
     * @param oreInfo 矿石信息
     * @return 是否视为自然矿
     */
    @Redirect(
        method = "getOreDrops(Ljava/util/Random;Lgregtech/common/ores/OreInfo;ZI)Ljava/util/ArrayList;",
        at = @At(value = "FIELD", target = "Lgregtech/common/ores/OreInfo;isNatural:Z"))
    private boolean qzMiner$allowPlacedOreFortune(OreInfo<?> oreInfo) {
        return FortuneCompatHelper.shouldTreatOreAsNatural(oreInfo.isNatural);
    }

    /**
     * 按配置绕过 GT 普通矿的时运 3 级截断。
     *
     * @param original 原版判断结果
     * @return 最终是否保留原版时运上限判断
     */
    @Definition(id = "fortuneLevel", local = @Local(type = int.class, argsOnly = true))
    @Expression("fortuneLevel > 3")
    @ModifyExpressionValue(
        method = "getBigOreDrops(Ljava/util/Random;Lgregtech/common/GTProxy$OreDropSystem;Lgregtech/common/ores/OreInfo;I)Ljava/util/ArrayList;",
        at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    private boolean qzMiner$removeNormalOreFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
