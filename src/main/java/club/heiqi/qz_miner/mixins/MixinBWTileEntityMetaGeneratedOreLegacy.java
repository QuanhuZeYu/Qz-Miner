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
    // 字段名实测为 mNatural（不是 natural）：在三档宿主字节码上核验过
    // GT5-Unofficial 5.09.51.470 / 5.09.51.482 的 BWTileEntityMetaGeneratedOre#getDrops(I)
    // 方法体里字段访问是 GETFIELD ...mNatural:Z，与 GT 侧 TileEntityOres 的命名一致。
    // 写错会让 @Definition 匹配失败，而本 mixin 是 require = 1 —— 不是静默失效而是启动即崩。
    // 能力门 QzMinerMixinPlugin 的同名字段判据必须与这里同源，改一处必须改两处。
    @Definition(id = "natural", field = "Lbartworks/system/material/BWTileEntityMetaGeneratedOre;mNatural:Z")
    @Expression("this.natural")
    @ModifyExpressionValue(method = "getDrops(I)Ljava/util/ArrayList;", at = @At("MIXINEXTRAS:EXPRESSION"), require = 1)
    private boolean qzMiner$treatPlacedOreAsNatural(boolean natural) {
        return FortuneCompatHelper.shouldTreatOreAsNatural(natural);
    }

    /** 拦截上游时运三级截断。 */
    @Definition(id = "fortuneLevel", local = @Local(type = int.class, argsOnly = true))
    @Expression("fortuneLevel > 3")
    @ModifyExpressionValue(method = "getDrops(I)Ljava/util/ArrayList;", at = @At("MIXINEXTRAS:EXPRESSION"), require = 1)
    private boolean qzMiner$removeFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
