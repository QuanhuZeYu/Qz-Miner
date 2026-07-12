package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 调整 BW 普通矿时运限制。
 */
@Pseudo
@Mixin(targets = "gregtech.common.ores.BWOreAdapter", remap = false)
public abstract class MixinBWOreAdapter {

    /**
     * 允许放置的 BW 矿也参与时运判断。
     *
     * @param original 上游自然矿标记
     * @return 是否视为自然矿
     */
    @ModifyExpressionValue(
        method = "getOreDrops(Ljava/util/Random;Lgregtech/common/ores/OreInfo;ZI)Ljava/util/ArrayList;",
        at = @At(value = "FIELD", target = "Lgregtech/common/ores/OreInfo;isNatural:Z"),
        require = 1)
    private boolean qzMiner$allowPlacedOreFortune(boolean original) {
        return FortuneCompatHelper.shouldTreatOreAsNatural(original);
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
        method = "getBigOreDrops(Ljava/util/Random;Lgregtech/common/GTProxy$OreDropSystem;Lgregtech/common/ores/OreInfo;I)Ljava/util/ArrayList;",
        at = @At(value = "MIXINEXTRAS:EXPRESSION"),
        require = 1)
    private boolean qzMiner$removeOreFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
