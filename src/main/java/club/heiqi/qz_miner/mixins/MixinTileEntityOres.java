package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import gregtech.common.blocks.TileEntityOres;
import net.minecraft.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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
     * 按配置解除 GT 普通矿时运 3 级上限。
     *
     * @param currentBound 原始随机上界
     * @param droppedOre 掉落方块
     * @param fortune 原始时运等级
     * @return 调整后的随机上界
     */
    @ModifyArg(
        method = "getDrops(Lnet/minecraft/block/Block;I)Ljava/util/ArrayList;",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 0),
        index = 0)
    private int qzMiner$removeNormalOreFortuneCap(int currentBound, Block droppedOre, int fortune) {
        return FortuneCompatHelper.resolveGtOreFortuneRollBound(currentBound, fortune);
    }
}
