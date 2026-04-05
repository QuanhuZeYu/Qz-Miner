package club.heiqi.qz_miner.mixins;

import club.heiqi.qz_miner.compat.FortuneCompatHelper;
import gtPlusPlus.core.block.base.BlockBaseOre;
import java.util.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 调整 GT++ 普通矿时运上限。
 */
@Mixin(value = BlockBaseOre.class, remap = false)
public abstract class MixinBlockBaseOre {

    /**
     * 按配置解除 GT++ 普通矿时运 3 级上限。
     *
     * @param random 随机数实例
     * @param currentBound 原始随机上界
     * @param world 世界对象
     * @param x 方块坐标 X
     * @param y 方块坐标 Y
     * @param z 方块坐标 Z
     * @param metadata 方块元数据
     * @param fortune 原始时运等级
     * @return 调整后的随机结果
     */
    @Redirect(
        method = "getDrops(Lnet/minecraft/world/World;IIIII)Ljava/util/ArrayList;",
        at = @At(value = "INVOKE", target = "Ljava/util/Random;nextInt(I)I", ordinal = 0))
    private int qzMiner$removeOreFortuneCap(Random random, int currentBound, World world, int x, int y, int z, int metadata, int fortune) {
        return random.nextInt(FortuneCompatHelper.resolveCommonOreFortuneRollBound(currentBound, fortune));
    }
}
