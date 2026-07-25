package club.heiqi.qz_miner.compat.adapter;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * Et Futurum Requiem 作物兼容适配器。
 */
public final class EtFuturumCropCompatAdapter implements CropCompatAdapter {

    private final Class<?> berryBushType;
    private final Class<?> caveVinesType;

    /**
     * 创建 EFR 作物适配器。
     */
    public EtFuturumCropCompatAdapter() {
        this(ClassNameCompatSupport.resolveClass("ganymedes01.etfuturum.blocks.BlockBerryBush"),
                ClassNameCompatSupport.resolveClass("ganymedes01.etfuturum.blocks.BaseCaveVines"));
    }

    /** 包级类型注入接缝，供纯 JVM metadata 矩阵测试。 */
    EtFuturumCropCompatAdapter(Class<?> berryBushType, Class<?> caveVinesType) {
        this.berryBushType = berryBushType;
        this.caveVinesType = caveVinesType;
    }

    @Override
    public boolean isAvailable() {
        return berryBushType != null || caveVinesType != null;
    }

    @Override
    public boolean isCropBlock(Block block, TileEntity tileEntity) {
        return ClassNameCompatSupport.isInstance(berryBushType, block)
            || ClassNameCompatSupport.isInstance(caveVinesType, block);
    }

    @Override
    public CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
            TileEntity tileEntity) {
        try {
            if (ClassNameCompatSupport.isInstance(berryBushType, block)) {
                if (metadata == 2 || metadata == 3) {
                    return CropGrowthState.MATURE;
                }
                if (metadata == 0 || metadata == 1) {
                    return CropGrowthState.IMMATURE;
                }
                return CropGrowthState.UNKNOWN;
            }
            if (ClassNameCompatSupport.isInstance(caveVinesType, block)) {
                if (metadata == 1) {
                    return CropGrowthState.MATURE;
                }
                if (metadata == 0) {
                    return CropGrowthState.IMMATURE;
                }
            }
            return CropGrowthState.UNKNOWN;
        } catch (RuntimeException | LinkageError failure) {
            return CropGrowthState.UNKNOWN;
        }
    }
}
