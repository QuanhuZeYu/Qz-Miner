package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.tileentity.TileEntity;

/**
 * 通过声明字段捕获 TileEntity 纯值身份的适配器。
 */
public final class ReflectiveFieldTileIdentityCompatAdapter implements TileIdentityCompatAdapter {

    private final Class<?> tileEntityType;
    private final String fieldName;
    private final String strategyId;
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
        this.strategyId = "reflective-field:" + String.valueOf(tileEntityClassName) + "#" + String.valueOf(fieldName);
    }

    @Override
    public boolean isAvailable() {
        return tileEntityType != null;
    }

    @Override
    public boolean supports(TileEntity tileEntity) {
        return ClassNameCompatSupport.isInstance(tileEntityType, tileEntity);
    }

    @Override
    public TileIdentityToken capture(TileEntity tileEntity) {
        Integer value = readIntField(tileEntity);
        if (value == null) {
            return TileIdentityToken.unresolved();
        }
        try {
            return TileIdentityToken.present(strategyId, tileEntityType.getName(), String.valueOf(value));
        } catch (RuntimeException | LinkageError failure) {
            return TileIdentityToken.unresolved();
        }
    }

    private Integer readIntField(TileEntity tileEntity) {
        if (tileEntity == null || fieldName == null) {
            return null;
        }

        Field field = resolveField(tileEntity.getClass());
        if (field == null) {
            return null;
        }

        try {
            return Integer.valueOf(field.getInt(tileEntity));
        } catch (IllegalAccessException | IllegalArgumentException | LinkageError | SecurityException ignored) {
            return null;
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
