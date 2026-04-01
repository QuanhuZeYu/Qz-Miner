package club.heiqi.qz_miner.event;

/**
 * 事件监听器接口
 * 所有事件监听器必须实现此接口
 * @param <T> 事件类型
 */
public interface EventListener<T extends BaseEvent> {
    
    /**
     * 处理事件
     * @param event 事件对象
     */
    void handleEvent(T event);
    
    /**
     * 获取监听器优先级
     * 优先级高的监听器先执行
     * @return 优先级
     */
    default int getPriority() {
        return 0;
    }
    
    /**
     * 获取监听器名称
     * @return 监听器名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}