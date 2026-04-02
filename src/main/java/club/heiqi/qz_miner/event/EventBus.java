package club.heiqi.qz_miner.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 函数式事件总线。
 *
 * 供模组内部和其他模组通过函数式接口订阅和分发事件。
 * 所有公共方法都是线程安全的。
 */
public final class EventBus {

    /**
     * 全局默认事件总线实例。
     */
    public static final EventBus INSTANCE = new EventBus();

    private final Map<Class<? extends Event>, List<ListenerEntry<? extends Event>>> listeners = new HashMap<>();

    private EventBus() {
    }

    /**
     * 以默认优先级注册一个事件监听器。
     *
     * @param eventType 事件类型
     * @param listener  监听器
     * @param <T>       事件类型
     */
    public <T extends Event> void register(Class<T> eventType, EventListener<T> listener) {
        register(eventType, listener, EventPriority.NORMAL);
    }

    /**
     * 注册一个事件监听器。
     *
     * @param eventType 事件类型
     * @param listener  监听器
     * @param priority  优先级
     * @param <T>       事件类型
     */
    @SuppressWarnings("unchecked")
    public synchronized <T extends Event> void register(Class<T> eventType, EventListener<T> listener, EventPriority priority) {
        listeners.computeIfAbsent(eventType, k -> new ArrayList<>())
                .add(new ListenerEntry<>((EventListener<? extends Event>) listener, priority));
        sortListeners(eventType);
    }

    /**
     * 移除指定事件类型的所有监听器。
     *
     * @param eventType 事件类型
     * @param <T>       事件类型
     */
    public synchronized <T extends Event> void unregister(Class<T> eventType) {
        listeners.remove(eventType);
    }

    /**
     * 移除指定事件类型的指定监听器。
     *
     * @param eventType 事件类型
     * @param listener  要移除的监听器
     * @param <T>       事件类型
     */
    @SuppressWarnings("unchecked")
    public synchronized <T extends Event> void unregister(Class<T> eventType, EventListener<T> listener) {
        List<ListenerEntry<? extends Event>> list = listeners.get(eventType);
        if (list != null) {
            list.removeIf(entry -> entry.listener == listener);
            if (list.isEmpty()) {
                listeners.remove(eventType);
            }
        }
    }

    /**
     * 触发一个事件，按优先级顺序同步通知所有已注册的监听器。
     *
     * @param event 事件实例
     * @param <T>   事件类型
     */
    @SuppressWarnings("unchecked")
    public <T extends Event> void post(T event) {
        List<ListenerEntry<? extends Event>> list;
        synchronized (this) {
            list = listeners.get(event.getClass());
            if (list == null) {
                return;
            }
        }
        for (ListenerEntry<? extends Event> entry : list) {
            try {
                ((EventListener<T>) entry.listener).onEvent(event);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends Event> void sortListeners(Class<T> eventType) {
        List<ListenerEntry<? extends Event>> list = listeners.get(eventType);
        if (list != null) {
            list.sort(Comparator.comparingInt(e -> e.priority.getValue()));
        }
    }

    /**
     * 内部监听器条目，携带优先级信息。
     */
    private static final class ListenerEntry<T extends Event> {
        final EventListener<T> listener;
        final EventPriority priority;

        ListenerEntry(EventListener<T> listener, EventPriority priority) {
            this.listener = listener;
            this.priority = priority;
        }
    }
}