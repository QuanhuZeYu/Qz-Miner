package club.heiqi.qz_miner.event;

/**
 * 函数式事件监听器接口。
 *
 * @param <T> 监听的事件类型
 */
@FunctionalInterface
public interface EventListener<T extends Event> {

    /**
     * 当事件被触发时调用。
     *
     * @param event 事件实例
     */
    void onEvent(T event);
}