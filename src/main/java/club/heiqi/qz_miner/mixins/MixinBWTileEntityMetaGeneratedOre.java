package club.heiqi.qz_miner.mixins;

import bartworks.system.material.BWTileEntityMetaGeneratedOre;
import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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
     * 按配置解除 BW 普通矿时运 3 级上限。
     *
     * @param currentBound 原始随机上界
     * @param fortune 原始时运等级
     * @return 调整后的随机上界
     */
    @ModifyArg(
        method = "getDrops(I)Ljava/util/ArrayList;",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 0),
        index = 0)
    private int qzMiner$removeOreFortuneCap(int currentBound, int fortune) {
        return FortuneCompatHelper.resolveCommonOreFortuneRollBound(currentBound, fortune);
    }
}
