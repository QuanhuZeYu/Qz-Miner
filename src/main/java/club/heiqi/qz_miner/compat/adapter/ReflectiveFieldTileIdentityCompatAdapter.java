package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.Field;

import net.minecraft.tileentity.TileEntity;

/**
 * 通过公开字段判定 TileEntity 身份的适配器。
 */
public final class ReflectiveFieldTileIdentityCompatAdapter implements TileIdentityCompatAdapter {

    private final Class<?> tileEntityType;
    private final String fieldName;

    /**
     * 创建字段型身份适配器。
     *
     * @param tileEntityClassName TileEntity 类名
     * @param fieldName 用于比较的字段名
     */
    public ReflectiveFieldTileIdentityCompatAdapter(String tileEntityClassName, String fieldName) {
        this.tileEntityType = ClassNameCompatSupport.resolveClass(tileEntityClassName);
        this.fieldName = fieldName;
    }

    @Override
    public boolean isAvailable() {
        return tileEntityType != null;
    }

    @Override
    public boolean supports(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        return ClassNameCompatSupport.isInstance(tileEntityType, sampleTileEntity)
            && ClassNameCompatSupport.isInstance(tileEntityType, targetTileEntity);
    }

    @Override
    public boolean matches(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        int sampleValue = readIntField(sampleTileEntity);
        int targetValue = readIntField(targetTileEntity);
        return sampleValue != Integer.MIN_VALUE && sampleValue == targetValue;
    }

    private int readIntField(TileEntity tileEntity) {
        if (tileEntity == null || fieldName == null) {
            return Integer.MIN_VALUE;
        }

        try {
            Field field = tileEntity.getClass().getField(fieldName);
            return field.getInt(tileEntity);
        } catch (ReflectiveOperationException ignored) {
            return Integer.MIN_VALUE;
        }
    }
}
