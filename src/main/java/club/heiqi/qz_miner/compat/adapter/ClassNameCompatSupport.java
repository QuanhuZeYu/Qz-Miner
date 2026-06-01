package club.heiqi.qz_miner.compat.adapter;

/**
 * 按类名探测可选模组类的工具。
 */
public final class ClassNameCompatSupport {

    private ClassNameCompatSupport() {}

    /**
     * 判断类名是否可解析。
     *
     * @param className 类名
     * @return 是否存在
     */
    public static boolean isClassPresent(String className) {
        return resolveClass(className) != null;
    }

    /**
     * 按类名解析 Class，缺失或依赖不完整时返回 null。
     *
     * 可选模组类只做存在性探测，不触发静态初始化，避免服务端加载客户端专属依赖。
     *
     * @param className 类名
     * @return 解析到的 Class
     */
    public static Class<?> resolveClass(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }

        try {
            return Class.forName(className, false, ClassNameCompatSupport.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return null;
        }
    }

    /**
     * 判断对象是否为指定类型实例。
     *
     * @param type 类型
     * @param instance 对象
     * @return 是否为实例
     */
    public static boolean isInstance(Class<?> type, Object instance) {
        return type != null && instance != null && type.isInstance(instance);
    }
}
