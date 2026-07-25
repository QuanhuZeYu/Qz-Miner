package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

/**
 * IC2/GT CropCard 作物兼容适配器。
 *
 * <p>只解析 IC2 API 的声明成员，不静态链接可选类型；GT CropCard 子类沿同一
 * {@code CropCard.canBeHarvested(ICropTile)} 权威入口分类。</p>
 */
public final class Ic2CropCompatAdapter implements CropCompatAdapter {

    private static final String TILE_ENTITY_CROP = "ic2.core.crop.TileEntityCrop";
    private static final String CROP_TILE = "ic2.api.crops.ICropTile";
    private static final String CROP_CARD = "ic2.api.crops.CropCard";

    private final Class<?> tileEntityCropType;
    private final Class<?> cropTileType;
    private final Class<?> cropCardType;
    private final Method getCropMethod;
    private final Method canBeHarvestedMethod;

    /** 创建生产 IC2 反射适配器。 */
    public Ic2CropCompatAdapter() {
        this(ClassNameCompatSupport.resolveClass(TILE_ENTITY_CROP),
                ClassNameCompatSupport.resolveClass(CROP_TILE),
                ClassNameCompatSupport.resolveClass(CROP_CARD));
    }

    /** 包级可注入类型接缝，供纯 JVM 测试覆盖成员缺失与调用异常。 */
    Ic2CropCompatAdapter(Class<?> tileEntityCropType, Class<?> cropTileType, Class<?> cropCardType) {
        this.tileEntityCropType = tileEntityCropType;
        this.cropTileType = cropTileType;
        this.cropCardType = cropCardType;
        this.getCropMethod = ReflectiveMemberSupport.findMethodInHierarchy(cropTileType, "getCrop");
        this.canBeHarvestedMethod = ReflectiveMemberSupport.findMethodInHierarchy(
                cropCardType, "canBeHarvested", cropTileType);
    }

    @Override
    public boolean isAvailable() {
        // 即使成员漂移也保留既有 TileEntity 作物识别，成熟度单独降级 UNKNOWN。
        return tileEntityCropType != null;
    }

    @Override
    public boolean isCropBlock(Block block, TileEntity tileEntity) {
        return ClassNameCompatSupport.isInstance(tileEntityCropType, tileEntity);
    }

    @Override
    public CropGrowthState growthState(World world, int x, int y, int z, Block block, int metadata,
            TileEntity tileEntity) {
        try {
            if (!isCropBlock(block, tileEntity)
                    || !ClassNameCompatSupport.isInstance(cropTileType, tileEntity)
                    || getCropMethod == null || canBeHarvestedMethod == null) {
                return CropGrowthState.UNKNOWN;
            }
            Object cropCard = getCropMethod.invoke(tileEntity);
            if (!ClassNameCompatSupport.isInstance(cropCardType, cropCard)) {
                return CropGrowthState.UNKNOWN;
            }
            Object result = canBeHarvestedMethod.invoke(cropCard, tileEntity);
            if (Boolean.TRUE.equals(result)) {
                return CropGrowthState.MATURE;
            }
            if (Boolean.FALSE.equals(result)) {
                return CropGrowthState.IMMATURE;
            }
            return CropGrowthState.UNKNOWN;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException | LinkageError failure) {
            return CropGrowthState.UNKNOWN;
        }
    }
}
