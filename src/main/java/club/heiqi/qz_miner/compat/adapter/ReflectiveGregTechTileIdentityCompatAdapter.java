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

    private static final String STRATEGY_ID = "gregtech-meta-tile";
    private static final String ABSENT_META_TYPE = "<absent-meta-tile>";

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
    public boolean supports(TileEntity tileEntity) {
        return ClassNameCompatSupport.isInstance(gregTechTileEntityType, tileEntity);
    }

    @Override
    public TileIdentityToken capture(TileEntity tileEntity) {
        InvocationResult metaIdResult = invoke(tileEntity, "getMetaTileID");
        InvocationResult metaTileResult = invoke(tileEntity, "getMetaTileEntity");
        if (!metaIdResult.success || !(metaIdResult.value instanceof Number) || !metaTileResult.success) {
            return TileIdentityToken.unresolved();
        }

        String metaTypeName = ABSENT_META_TYPE;
        if (metaTileResult.value != null) {
            try {
                metaTypeName = metaTileResult.value.getClass().getName();
            } catch (LinkageError | SecurityException ignored) {
                return TileIdentityToken.unresolved();
            }
        }
        String identityKey = ((Number) metaIdResult.value).intValue() + "|" + metaTypeName;
        try {
            return TileIdentityToken.present(STRATEGY_ID, gregTechTileEntityType.getName(), identityKey);
        } catch (RuntimeException | LinkageError failure) {
            return TileIdentityToken.unresolved();
        }
    }

    private InvocationResult invoke(Object owner, String methodName) {
        if (owner == null) {
            return InvocationResult.failure();
        }

        Method method = resolveMethod(owner.getClass(), methodName);
        if (method == null) {
            return InvocationResult.failure();
        }

        try {
            return InvocationResult.success(method.invoke(owner));
        } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException
                | LinkageError | SecurityException ignored) {
            return InvocationResult.failure();
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

        Method resolvedMethod = ReflectiveMemberSupport.findMethodInHierarchy(ownerType, methodName);
        if (resolvedMethod == null) {
            missingMethods.add(cacheKey);
            return null;
        }
        Method previousMethod = methodCache.putIfAbsent(cacheKey, resolvedMethod);
        return previousMethod == null ? resolvedMethod : previousMethod;
    }

    /** 区分反射成功返回 null 与反射失败。 */
    private static final class InvocationResult {
        private final boolean success;
        private final Object value;

        private InvocationResult(boolean success, Object value) {
            this.success = success;
            this.value = value;
        }

        private static InvocationResult success(Object value) {
            return new InvocationResult(true, value);
        }

        private static InvocationResult failure() {
            return new InvocationResult(false, null);
        }
    }
}
