package club.heiqi.qz_miner.compat.adapter;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 可选模组反射成员解析工具。
 *
 * 反射可选模组时不能让缺失依赖或客户端专属签名传播到初始化流程，
 * 否则 dedicated server 会在兼容能力探测阶段直接崩溃。
 */
public final class ReflectiveMemberSupport {

    private ReflectiveMemberSupport() {}

    /**
     * 沿类层级查找声明方法。
     *
     * @param ownerType 起始类型
     * @param methodName 方法名
     * @param parameterTypes 参数类型
     * @return 可访问方法，缺失或签名依赖不可解析时返回 null
     */
    public static Method findMethodInHierarchy(Class<?> ownerType, String methodName, Class<?>... parameterTypes) {
        if (ownerType == null || methodName == null || methodName.isEmpty()) {
            return null;
        }

        Class<?> currentType = ownerType;
        while (currentType != null) {
            Method method = resolveDeclaredMethod(currentType, methodName, parameterTypes);
            if (method != null) {
                return method;
            }
            currentType = currentType.getSuperclass();
        }
        return null;
    }

    /**
     * 沿类层级查找声明字段。
     *
     * @param ownerType 起始类型
     * @param fieldName 字段名
     * @return 可访问字段，缺失或签名依赖不可解析时返回 null
     */
    public static Field findFieldInHierarchy(Class<?> ownerType, String fieldName) {
        if (ownerType == null || fieldName == null || fieldName.isEmpty()) {
            return null;
        }

        Class<?> currentType = ownerType;
        while (currentType != null) {
            Field field = resolveDeclaredField(currentType, fieldName);
            if (field != null) {
                return field;
            }
            currentType = currentType.getSuperclass();
        }
        return null;
    }

    private static Method resolveDeclaredMethod(Class<?> ownerType, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = ownerType.getDeclaredMethod(methodName, parameterTypes);
            return makeAccessible(method) ? method : null;
        } catch (NoSuchMethodException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private static Field resolveDeclaredField(Class<?> ownerType, String fieldName) {
        try {
            Field field = ownerType.getDeclaredField(fieldName);
            return makeAccessible(field) ? field : null;
        } catch (NoSuchFieldException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    private static boolean makeAccessible(AccessibleObject accessibleObject) {
        try {
            accessibleObject.setAccessible(true);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }
}
