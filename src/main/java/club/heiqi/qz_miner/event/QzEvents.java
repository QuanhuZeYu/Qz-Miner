package club.heiqi.qz_miner.event;

/**
 * Qz Miner 事件系统的公共 API 入口。
 *
 * 其他模组可以通过此类访问事件总线，注册监听器或触发事件。
 * 内部模组也可以直接使用此类，避免直接依赖 {@link EventBus} 实例。
 */
public final class QzEvents {

    /**
     * 获取全局事件总线实例。
     *
     * @return 事件总线
     */
    public static EventBus bus() {
        return EventBus.INSTANCE;
    }

    /**
     * 以默认优先级注册一个事件监听器。
     *
     * @param eventType 事件类型
     * @param listener  监听器
     * @param <T>       事件类型
     */
    public static <T extends Event> void register(Class<T> eventType, EventListener<T> listener) {
        EventBus.INSTANCE.register(eventType, listener);
    }

    /**
     * 注册一个事件监听器。
     *
     * @param eventType 事件类型
     * @param listener  监听器
     * @param priority  优先级
     * @param <T>       事件类型
     */
    public static <T extends Event> void register(Class<T> eventType, EventListener<T> listener, EventPriority priority) {
        EventBus.INSTANCE.register(eventType, listener, priority);
    }

    /**
     * 移除指定事件类型的指定监听器。
     *
     * @param eventType 事件类型
     * @param listener  要移除的监听器
     * @param <T>       事件类型
     */
    public static <T extends Event> void unregister(Class<T> eventType, EventListener<T> listener) {
        EventBus.INSTANCE.unregister(eventType, listener);
    }

    /**
     * 移除指定事件类型的所有监听器。
     *
     * @param eventType 事件类型
     * @param <T>       事件类型
     */
    public static <T extends Event> void unregisterAll(Class<T> eventType) {
        EventBus.INSTANCE.unregister(eventType);
    }

    /**
     * 触发一个事件。
     *
     * @param event 事件实例
     * @param <T>   事件类型
     */
    public static <T extends Event> void post(T event) {
        EventBus.INSTANCE.post(event);
    }

    private QzEvents() {
    }
}