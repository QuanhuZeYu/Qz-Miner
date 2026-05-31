package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import gregtech.common.blocks.TileEntityOres;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 调整 GT 矿石时运限制。
 */
@Mixin(value = TileEntityOres.class, remap = false)
public abstract class MixinTileEntityOres {

    @Shadow
    public boolean mNatural;

    /**
     * 允许放置的 GT 普通矿也参与时运判断。
     *
     * @param tileEntity 矿石实体
     * @return 是否视为自然矿
     */
    @Redirect(
        method = "getDrops(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;",
        at = @At(value = "FIELD", target = "Lgregtech/common/blocks/TileEntityOres;mNatural:Z", ordinal = 0))
    private boolean qzMiner$allowPlacedNormalOreFortune(TileEntityOres tileEntity) {
        return FortuneCompatHelper.shouldTreatOreAsNatural(tileEntity.mNatural);
    }

    /**
     * 允许放置的 GT 小矿保留时运等级。
     *
     * @param tileEntity 矿石实体
     * @return 是否视为自然矿
     */
    @Redirect(
        method = "getDrops(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;",
        at = @At(value = "FIELD", target = "Lgregtech/common/blocks/TileEntityOres;mNatural:Z", ordinal = 1))
    private boolean qzMiner$allowPlacedSmallOreFortune(TileEntityOres tileEntity) {
        return FortuneCompatHelper.shouldTreatOreAsNatural(tileEntity.mNatural);
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
        method = "getDrops(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;",
        at = @At(value = "MIXINEXTRAS:EXPRESSION"))
    private boolean qzMiner$removeNormalOreFortuneCap(boolean original) {
        return FortuneCompatHelper.shouldKeepFortuneCapCheck(original);
    }
}
