package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.tileentity.TileEntity;

/**
 * 通过公开字段判定 TileEntity 身份的适配器。
 */
public final class ReflectiveFieldTileIdentityCompatAdapter implements TileIdentityCompatAdapter {

    private final Class<?> tileEntityType;
    private final String fieldName;
    private final Map<String, Field> fieldCache = new ConcurrentHashMap<String, Field>();
    private final Set<String> missingFields = ConcurrentHashMap.newKeySet();

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

        Field field = resolveField(tileEntity.getClass());
        if (field == null) {
            return Integer.MIN_VALUE;
        }

        try {
            return field.getInt(tileEntity);
        } catch (ReflectiveOperationException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private Field resolveField(Class<?> ownerType) {
        if (ownerType == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }

        String cacheKey = ownerType.getName() + "#" + fieldName;
        Field cachedField = fieldCache.get(cacheKey);
        if (cachedField != null) {
            return cachedField;
        }
        if (missingFields.contains(cacheKey)) {
            return null;
        }

        Field resolvedField = ReflectiveMemberSupport.findFieldInHierarchy(ownerType, fieldName);
        if (resolvedField == null) {
            missingFields.add(cacheKey);
            return null;
        }
        Field previousField = fieldCache.putIfAbsent(cacheKey, resolvedField);
        return previousField == null ? resolvedField : previousField;
    }
}
