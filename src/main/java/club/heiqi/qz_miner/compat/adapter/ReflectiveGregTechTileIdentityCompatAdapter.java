package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.tileentity.TileEntity;

/**
 * 通过 GregTech 元实体信息判定 TileEntity 身份的适配器。
 */
public final class ReflectiveGregTechTileIdentityCompatAdapter implements TileIdentityCompatAdapter {

    private final Class<?> gregTechTileEntityType;
    private final Map<String, Method> methodCache = new ConcurrentHashMap<String, Method>();
    private final Set<String> missingMethods = ConcurrentHashMap.newKeySet();

    /**
     * 创建 GregTech TileEntity 身份适配器。
     */
    public ReflectiveGregTechTileIdentityCompatAdapter() {
        this.gregTechTileEntityType = ClassNameCompatSupport.resolveClass("gregtech.api.interfaces.tileentity.IGregTechTileEntity");
    }

    @Override
    public boolean isAvailable() {
        return gregTechTileEntityType != null;
    }

    @Override
    public boolean supports(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        return ClassNameCompatSupport.isInstance(gregTechTileEntityType, sampleTileEntity)
            && ClassNameCompatSupport.isInstance(gregTechTileEntityType, targetTileEntity);
    }

    @Override
    public boolean matches(TileEntity sampleTileEntity, TileEntity targetTileEntity) {
        if (invokeInt(sampleTileEntity, "getMetaTileID") != invokeInt(targetTileEntity, "getMetaTileID")) {
            return false;
        }

        Object sampleMetaTileEntity = invoke(sampleTileEntity, "getMetaTileEntity");
        Object targetMetaTileEntity = invoke(targetTileEntity, "getMetaTileEntity");
        if (sampleMetaTileEntity == null || targetMetaTileEntity == null) {
            return sampleMetaTileEntity == targetMetaTileEntity;
        }
        return sampleMetaTileEntity.getClass() == targetMetaTileEntity.getClass();
    }

    private int invokeInt(Object owner, String methodName) {
        Object value = invoke(owner, methodName);
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    private Object invoke(Object owner, String methodName) {
        if (owner == null) {
            return null;
        }

        Method method = resolveMethod(owner.getClass(), methodName);
        if (method == null) {
            return null;
        }

        try {
            return method.invoke(owner);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
            return null;
        }
    }

    private Method resolveMethod(Class<?> ownerType, String methodName) {
        if (ownerType == null || methodName == null || methodName.isEmpty()) {
            return null;
        }

        String cacheKey = ownerType.getName() + "#" + methodName;
        Method cachedMethod = methodCache.get(cacheKey);
        if (cachedMethod != null) {
            return cachedMethod;
        }
        if (missingMethods.contains(cacheKey)) {
            return null;
        }

        try {
            Method resolvedMethod = ownerType.getMethod(methodName);
            Method previousMethod = methodCache.putIfAbsent(cacheKey, resolvedMethod);
            return previousMethod == null ? resolvedMethod : previousMethod;
        } catch (NoSuchMethodException ignored) {
            missingMethods.add(cacheKey);
            return null;
        }
    }
}
